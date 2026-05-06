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

	// Dark-mode toggle is wired in on the (auth) layout — flipping it
	// should add `class="dark"` to <html>, and flipping again removes it.
	const html = page.locator('html');
	const wasDark = (await html.getAttribute('class'))?.includes('dark') ?? false;
	await page.getByRole('button', { name: 'Toggle dark mode' }).click();
	if (wasDark) {
		await expect(html).not.toHaveClass(/(^|\s)dark(\s|$)/);
	} else {
		await expect(html).toHaveClass(/(^|\s)dark(\s|$)/);
	}
	// Restore so subsequent screenshots aren't surprising.
	await page.getByRole('button', { name: 'Toggle dark mode' }).click();

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
	// Wait a tick so any layout that runs on hydrate has settled.
	await page.waitForLoadState('networkidle');
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
