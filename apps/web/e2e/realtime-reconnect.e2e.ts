import { expect, test } from '@playwright/test';

import {
	cleanupTestData,
	fetchVerifyToken,
	gotoHydrated,
	promoteUser,
	uniqueEmail
} from './helpers';

/**
 * Sanity #3 has two halves:
 *
 *   (a) Browser-native EventSource reconnects after a network blip.
 *   (b) Our SvelteKit wrapper doesn't break that on the way through.
 *
 * (a) is browser-vendor responsibility — `EventSource` re-attempts
 * with ~3 s jittered backoff on any unexpected close. The server-side
 * half (heartbeat correctly evicts a dead emitter so the response
 * actually closes) is pinned by SseHeartbeatIntegrationTest. Trying to
 * exercise the full closed-loop in Playwright is unreliable: Chromium's
 * `setOffline(true)` blocks packets but doesn't actively reset open
 * sockets, so a short blip leaves the connection half-living and a
 * long blip pushes the test past CI timeouts.
 *
 * What this e2e owns reliably is (b): the wrapper's lifecycle across
 * a /schedule unmount → remount cycle. SvelteKit's component cleanup
 * has to call `EventSource.close()` and the next mount has to open a
 * fresh stream — if either drifts, nothing else compensates. Concrete
 * regressions this catches:
 *
 *   - Wrapper leaks the previous EventSource (no close on onDestroy)
 *   - Reactive store doesn't re-subscribe after navigation
 *   - Second connection lands but its events don't reach the UI
 *
 * The two structural assertions are: (1) /api/v1/events HTTP count
 * grows after the navigation cycle (a new stream actually opened),
 * and (2) a booking created in ctxB after the cycle still surfaces
 * live in ctxA's schedule.
 */

test.beforeAll(async () => {
	await cleanupTestData();
});

test('SSE re-establishes across a /schedule navigation cycle and still delivers events', async ({
	browser
}) => {
	const email = uniqueEmail('rt-reconnect');
	const password = 'first long valid passphrase 99';
	const displayName = 'Dr Reconnect';

	const ctxA = await browser.newContext({ ignoreHTTPSErrors: true });
	const pageA = await ctxA.newPage();

	// Native EventSource fires one GET /api/v1/events per (re)connection.
	// Counting requests on pageA is the structural signal — independent
	// of any in-page state and impossible to fake without a real network
	// hop.
	let sseRequestCount = 0;
	pageA.on('request', (req) => {
		if (req.url().includes('/api/v1/events')) sseRequestCount++;
	});

	// ---- bootstrap a fresh user via context A -------------------------------
	await gotoHydrated(pageA, '/register');
	await pageA.locator('#email').fill(email);
	await pageA.locator('#displayName').fill(displayName);
	await pageA.locator('#password').fill(password);
	await pageA.locator('button[type=submit]').click();
	await expect(pageA.getByText('Check your inbox')).toBeVisible();

	const verifyToken = await fetchVerifyToken(email);
	await gotoHydrated(pageA, `/verify?token=${verifyToken}`);
	await expect(pageA.getByText('Email verified')).toBeVisible();

	await promoteUser(email, { role: 'MANAGER', surgeonSpecialty: 'GENERAL' });

	await gotoHydrated(pageA, '/login');
	await pageA.locator('#email').fill(email);
	await pageA.locator('#password').fill(password);
	await pageA.locator('button[type=submit]').click();
	await expect(pageA).toHaveURL('/');

	// ---- create the OR (still in A) -----------------------------------------
	const orCode = `RT-RC-${Date.now().toString().slice(-5)}`;
	await gotoHydrated(pageA, '/operating-rooms');
	await pageA.locator('#code').fill(orCode);
	await pageA.locator('#name').fill('Reconnect Suite');
	await pageA.getByRole('button', { name: /^Add room$/ }).click();
	await expect(pageA.getByText(`Added "${orCode}".`)).toBeVisible();

	// ---- first /schedule visit: initial SSE connection opens ----------------
	await gotoHydrated(pageA, '/schedule');
	const orColumnA = pageA.locator(`div[aria-label="Create booking in ${orCode}"]`);
	await expect(orColumnA).toBeVisible();
	await expect.poll(() => sseRequestCount, { timeout: 5_000 }).toBeGreaterThanOrEqual(1);
	const afterFirstMount = sseRequestCount;

	// ---- navigation cycle: leave /schedule, then come back ------------------
	// Going to a route that doesn't subscribe to bookings forces the
	// schedule component to unmount, which must call EventSource.close.
	// The next /schedule visit has to mount fresh and open a new stream.
	await gotoHydrated(pageA, '/operating-rooms');
	await gotoHydrated(pageA, '/schedule');
	await expect(pageA.locator(`div[aria-label="Create booking in ${orCode}"]`)).toBeVisible();

	// Structural proof a fresh /api/v1/events request landed — not the
	// same socket reused.
	await expect.poll(() => sseRequestCount, { timeout: 10_000 }).toBeGreaterThan(afterFirstMount);

	// ---- copy A's session into a fresh context B ----------------------------
	const cookies = await ctxA.cookies();
	const ctxB = await browser.newContext({ ignoreHTTPSErrors: true });
	await ctxB.addCookies(cookies);
	const pageB = await ctxB.newPage();

	// ---- create a booking from ctxB; ctxA reflects it via the new stream ---
	await gotoHydrated(pageB, '/schedule');
	const orColumnB = pageB.locator(`div[aria-label="Create booking in ${orCode}"]`);
	await expect(orColumnB).toBeVisible();

	await orColumnB.click({ position: { x: 50, y: 90 } });
	const dialog = pageB.locator('dialog[open]');
	await expect(dialog).toBeVisible();
	await dialog.locator('#bf-start').fill('09:00');
	await dialog.locator('#bf-end').fill('10:00');
	await dialog.locator('#bf-optype').fill('Post-remount test');
	await dialog.locator('#bf-patient').fill(`P-9999-${Date.now().toString().slice(-3)}`);
	await dialog.getByRole('button', { name: /^Create booking$/ }).click();
	await expect(pageB.getByText('Booking created.')).toBeVisible();

	// pageA picks up the new block via the second-mount SSE stream.
	const blockA = pageA
		.locator(`div[aria-label="Create booking in ${orCode}"]`)
		.locator('button', { hasText: '09:00–10:00' });
	await expect(blockA).toBeVisible({ timeout: 10_000 });

	await ctxA.close();
	await ctxB.close();
});
