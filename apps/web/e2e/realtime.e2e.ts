import { expect, test } from '@playwright/test';

import {
	cleanupTestData,
	fetchVerifyToken,
	gotoHydrated,
	promoteUser,
	uniqueEmail
} from './helpers';

/**
 * Real-time coverage: a booking action in browser context A reflects in
 * browser context B without a manual refresh. Drives the SSE pipe end to
 * end — frontend EventSource → backend `/api/v1/events` → Redis pub/sub →
 * SseService → frontend handler → `loadSchedule()`.
 *
 * Two contexts as the same user keeps the seed surface small. The point
 * is the cross-tab broadcast, not multi-user fan-out.
 */

test.beforeAll(async () => {
	await cleanupTestData();
});

test('booking actions in tab A reflect live in tab B', async ({ browser }) => {
	const email = uniqueEmail('rt-e2e');
	const password = 'first long valid passphrase 99';
	const displayName = 'Dr Realtime';

	// ---- bootstrap a fresh user via context A -------------------------------
	const ctxA = await browser.newContext({ ignoreHTTPSErrors: true });
	const pageA = await ctxA.newPage();

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
	const orCode = `RT-${Date.now().toString().slice(-5)}`;
	await gotoHydrated(pageA, '/operating-rooms');
	await pageA.locator('#code').fill(orCode);
	await pageA.locator('#name').fill('Realtime Suite');
	await pageA.getByRole('button', { name: /^Add room$/ }).click();
	await expect(pageA.getByText(`Added "${orCode}".`)).toBeVisible();

	// ---- copy A's session into a fresh context B ----------------------------
	const cookies = await ctxA.cookies();
	const ctxB = await browser.newContext({ ignoreHTTPSErrors: true });
	await ctxB.addCookies(cookies);
	const pageB = await ctxB.newPage();

	// ---- both tabs land on /schedule, both see no bookings yet --------------
	await gotoHydrated(pageA, '/schedule');
	await gotoHydrated(pageB, '/schedule');

	const orColumnA = pageA.locator(`div[aria-label="Create booking in ${orCode}"]`);
	const orColumnB = pageB.locator(`div[aria-label="Create booking in ${orCode}"]`);
	await expect(orColumnA).toBeVisible();
	await expect(orColumnB).toBeVisible();

	// ---- create a booking in A ----------------------------------------------
	await orColumnA.click({ position: { x: 50, y: 90 } });
	const dialog = pageA.locator('dialog[open]');
	await expect(dialog).toBeVisible();
	await dialog.locator('#bf-start').fill('09:00');
	await dialog.locator('#bf-end').fill('10:00');
	await dialog.locator('#bf-optype').fill('Live update test');
	await dialog.locator('#bf-patient').fill(`P-9999-${Date.now().toString().slice(-3)}`);
	await dialog.getByRole('button', { name: /^Create booking$/ }).click();
	await expect(pageA.getByText('Booking created.')).toBeVisible();

	// ---- B sees the new block within ~5s, no manual refresh -----------------
	const blockB = pageB
		.locator(`div[aria-label="Create booking in ${orCode}"]`)
		.locator('button', { hasText: '09:00–10:00' });
	await expect(blockB).toBeVisible({ timeout: 5_000 });

	// ---- cancel in A, status flips in B's panel -----------------------------
	const blockA = pageA
		.locator(`div[aria-label="Create booking in ${orCode}"]`)
		.locator('button', { hasText: '09:00–10:00' });
	// Click in B first so B's side panel opens on the booking — that lets us
	// assert the panel reflects the cancelled status, not just the block.
	await blockB.click();
	const panelB = pageB.getByRole('complementary', { name: 'Booking details' });
	await expect(panelB).toBeVisible();
	await expect(panelB.getByText('scheduled', { exact: true })).toBeVisible();

	// Now cancel in A.
	await blockA.click();
	const panelA = pageA.getByRole('complementary', { name: 'Booking details' });
	pageA.on('dialog', (d) => d.accept());
	await panelA.getByRole('button', { name: /^Cancel$/ }).click();
	await expect(pageA.getByText('Booking cancelled.')).toBeVisible();

	// B's panel updates from the SSE-triggered re-fetch.
	await expect(panelB.getByText('cancelled', { exact: true })).toBeVisible({ timeout: 5_000 });

	await ctxA.close();
	await ctxB.close();
});
