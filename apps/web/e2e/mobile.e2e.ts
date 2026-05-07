import { expect, test } from '@playwright/test';

import {
	cleanupTestData,
	fetchVerifyToken,
	gotoHydrated,
	promoteUser,
	uniqueEmail
} from './helpers';

/**
 * Mobile viewport sanity at 375px (iPhone SE-ish). Verifies the core
 * authenticated routes don't leak horizontal scroll on the body and that
 * the headline element of each route is visible. The day-view grid is
 * intentionally allowed to overflow horizontally inside its own scroll
 * container — body-level overflow is what we guard against, since that
 * would mean the layout itself broke.
 */

const VIEWPORT = { width: 375, height: 812 };

test.use({ viewport: VIEWPORT });

test.beforeAll(async () => {
	await cleanupTestData();
});

test('login + home + schedule + ORs + clinic settings stay within 375px', async ({ page }) => {
	const email = uniqueEmail('mobile-e2e');
	const password = 'first long valid passphrase 99';
	const displayName = 'Mobile Tester';

	// /login renders pre-auth — confirm it fits.
	await gotoHydrated(page, '/login');
	await assertNoBodyHScroll(page);
	await expect(page.locator('#email')).toBeVisible();

	// Dark-mode toggle is wired in on the (auth) layout. The handler is
	// bound during Svelte hydration which can lag a moment behind the
	// document's `load` event — so click-then-assert can race the first
	// time. `expect.toPass` retries the click+assert pair until the class
	// flips, which converges as soon as the handler is bound.
	const html = page.locator('html');
	const startedDark = (await html.getAttribute('class'))?.includes('dark') ?? false;
	await expect(async () => {
		await page.getByRole('button', { name: 'Toggle dark mode' }).click();
		const isDark = (await html.getAttribute('class'))?.includes('dark') ?? false;
		if (isDark === startedDark) throw new Error('toggle had no effect yet');
	}).toPass({ timeout: 5_000 });
	// Restore so subsequent screenshots aren't surprising. Same toPass
	// pattern in case the second click also races.
	await expect(async () => {
		await page.getByRole('button', { name: 'Toggle dark mode' }).click();
		const isDark = (await html.getAttribute('class'))?.includes('dark') ?? false;
		if (isDark !== startedDark) throw new Error('restore had no effect yet');
	}).toPass({ timeout: 5_000 });

	// Walk through register → verify so we have a session.
	await gotoHydrated(page, '/register');
	await assertNoBodyHScroll(page);
	await page.locator('#email').fill(email);
	await page.locator('#displayName').fill(displayName);
	await page.locator('#password').fill(password);
	await page.locator('button[type=submit]').click();
	await expect(page.getByText('Check your inbox')).toBeVisible();

	const verifyToken = await fetchVerifyToken(email);
	await gotoHydrated(page, `/verify?token=${verifyToken}`);
	await expect(page.getByText('Email verified')).toBeVisible();

	// ADMIN here so we can also visit /settings/clinic.
	await promoteUser(email, { role: 'ADMIN' });

	await gotoHydrated(page, '/login');
	await page.locator('#email').fill(email);
	await page.locator('#password').fill(password);
	await page.locator('button[type=submit]').click();
	await expect(page).toHaveURL('/');

	// Home dashboard — every tile we have should be visible (ADMIN sees all).
	await assertNoBodyHScroll(page);
	await expect(page.getByTestId('tile-schedule')).toBeVisible();
	await expect(page.getByTestId('tile-operating-rooms')).toBeVisible();
	await expect(page.getByTestId('tile-clinic-settings')).toBeVisible();
	await expect(page.getByTestId('tile-security')).toBeVisible();

	// Operating rooms management page.
	await gotoHydrated(page, '/operating-rooms');
	await assertNoBodyHScroll(page);
	await expect(page.getByRole('heading', { name: 'Operating rooms' })).toBeVisible();

	// Clinic settings.
	await gotoHydrated(page, '/settings/clinic');
	await assertNoBodyHScroll(page);
	await expect(page.getByRole('heading', { name: 'Clinic settings' })).toBeVisible();

	// Security settings.
	await gotoHydrated(page, '/settings/security');
	await assertNoBodyHScroll(page);
	await expect(page.getByRole('heading', { name: 'Security' })).toBeVisible();

	// Schedule. The grid lives inside an `overflow-x-auto` container, so
	// body-level horizontal scroll should still be absent.
	await gotoHydrated(page, '/schedule');
	await assertNoBodyHScroll(page);
	await expect(page.getByRole('heading', { name: 'Schedule' })).toBeVisible();
});

async function assertNoBodyHScroll(page: import('@playwright/test').Page): Promise<void> {
	// `gotoHydrated` already returned past the 'load' boundary so layout is
	// settled — and we can't wait for `networkidle` on the SSE-enabled
	// routes (the long-lived /events stream means the network is never
	// idle). Measure straight away.
	const overflow = await page.evaluate(() => ({
		bodyScrollWidth: document.body.scrollWidth,
		bodyClientWidth: document.body.clientWidth,
		viewport: window.innerWidth
	}));
	// Allow a 1px rounding fudge — sub-pixel scrollbars on some platforms
	// otherwise produce false positives.
	expect(
		overflow.bodyScrollWidth - overflow.bodyClientWidth,
		`body overflows viewport (scrollWidth=${overflow.bodyScrollWidth}, clientWidth=${overflow.bodyClientWidth})`
	).toBeLessThanOrEqual(1);
}
