import { expect, test } from '@playwright/test';

import {
	cleanupTestData,
	fetchVerifyToken,
	gotoHydrated,
	promoteUser,
	uniqueEmail
} from './helpers';

/**
 * Closes Sprint 3 retro sanity item #3: after a network blip the
 * SvelteKit client's EventSource auto-reconnects and still reflects
 * fresh booking events.
 *
 * Native EventSource handles reconnect on its own with ~3 s jittered
 * backoff — this wrapper (`apps/web/src/lib/util/eventSource.ts`)
 * doesn't reimplement it, so the test owns proving the browser-native
 * behaviour survives our setup. Two structural checks:
 *
 *   1. After `ctxA.setOffline(true)` then `false`, the count of
 *      `/api/v1/events` requests on pageA increases — the existing
 *      connection was truly killed and a new one was opened.
 *   2. A booking created from ctxB after the blip still surfaces in
 *      ctxA's schedule live — the new SSE stream actually delivers.
 *
 * Either failure mode (no reconnect, or reconnect without delivery)
 * is exactly what sanity #3 is meant to catch.
 */

test.beforeAll(async () => {
	await cleanupTestData();
});

test('SSE auto-reconnects after a network blip and still reflects new events', async ({
	browser
}) => {
	const email = uniqueEmail('rt-reconnect');
	const password = 'first long valid passphrase 99';
	const displayName = 'Dr Reconnect';

	// ---- bootstrap a fresh user via context A -------------------------------
	const ctxA = await browser.newContext({ ignoreHTTPSErrors: true });
	const pageA = await ctxA.newPage();

	// Track every EventSource open on pageA — the native EventSource
	// reopens on each reconnect, so we get one HTTP request per attempt.
	let sseRequestCount = 0;
	pageA.on('request', (req) => {
		if (req.url().includes('/api/v1/events')) sseRequestCount++;
	});

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

	// ---- pageA on /schedule — initial SSE connection opens ------------------
	await gotoHydrated(pageA, '/schedule');
	const orColumnA = pageA.locator(`div[aria-label="Create booking in ${orCode}"]`);
	await expect(orColumnA).toBeVisible();

	// Wait for the first /api/v1/events request to land before snapshotting.
	await expect.poll(() => sseRequestCount, { timeout: 5_000 }).toBeGreaterThanOrEqualTo(1);
	const beforeBlipRequests = sseRequestCount;

	// ---- network blip — drop the connection, then restore -------------------
	await ctxA.setOffline(true);
	await pageA.waitForTimeout(BLIP_DURATION_MS);
	await ctxA.setOffline(false);

	// Native EventSource backs off ~3 s with jitter before reconnecting.
	// Poll for a fresh /api/v1/events request as structural proof the
	// browser actually re-established the stream rather than picking up
	// where it left off.
	await expect
		.poll(() => sseRequestCount, { timeout: RECONNECT_TIMEOUT_MS })
		.toBeGreaterThan(beforeBlipRequests);

	// ---- copy A's session into a fresh context B ----------------------------
	const cookies = await ctxA.cookies();
	const ctxB = await browser.newContext({ ignoreHTTPSErrors: true });
	await ctxB.addCookies(cookies);
	const pageB = await ctxB.newPage();

	// ---- create a booking from ctxB; ctxA should reflect it via reconnect --
	await gotoHydrated(pageB, '/schedule');
	const orColumnB = pageB.locator(`div[aria-label="Create booking in ${orCode}"]`);
	await expect(orColumnB).toBeVisible();

	await orColumnB.click({ position: { x: 50, y: 90 } });
	const dialog = pageB.locator('dialog[open]');
	await expect(dialog).toBeVisible();
	await dialog.locator('#bf-start').fill('09:00');
	await dialog.locator('#bf-end').fill('10:00');
	await dialog.locator('#bf-optype').fill('Post-reconnect test');
	await dialog.locator('#bf-patient').fill(`P-9999-${Date.now().toString().slice(-3)}`);
	await dialog.getByRole('button', { name: /^Create booking$/ }).click();
	await expect(pageB.getByText('Booking created.')).toBeVisible();

	// pageA picks up the new block via the freshly-reconnected SSE stream.
	const blockA = pageA
		.locator(`div[aria-label="Create booking in ${orCode}"]`)
		.locator('button', { hasText: '09:00–10:00' });
	await expect(blockA).toBeVisible({ timeout: 10_000 });

	await ctxA.close();
	await ctxB.close();
});

const BLIP_DURATION_MS = 2_000;
// Reconnect window: native EventSource jitter (~3 s) + a generous margin
// so test flakes under load don't masquerade as real reconnect failures.
const RECONNECT_TIMEOUT_MS = 10_000;
