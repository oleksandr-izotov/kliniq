import { expect, test } from '@playwright/test';

import { fetchVerifyToken, gotoHydrated, uniqueEmail } from './helpers';

test('register, verify, login, change password, logout — all via the SPA', async ({ page }) => {
	const email = uniqueEmail('e2e');
	const password = 'first long valid passphrase 99';
	const newPassword = 'second long valid passphrase 99';
	const displayName = 'E2E Tester';

	// ---- /register ---------------------------------------------------------
	await gotoHydrated(page, '/register');
	await page.locator('#email').fill(email);
	await page.locator('#displayName').fill(displayName);
	await page.locator('#password').fill(password);
	await page.locator('button[type=submit]').click();
	await expect(page.getByText('Check your inbox')).toBeVisible();

	// ---- mailpit → token → /verify ----------------------------------------
	const verifyToken = await fetchVerifyToken(email);
	await gotoHydrated(page, `/verify?token=${verifyToken}`);
	await expect(page.getByText('Email verified')).toBeVisible();

	// ---- /login -----------------------------------------------------------
	await gotoHydrated(page, '/login');
	await page.locator('#email').fill(email);
	await page.locator('#password').fill(password);
	await page.locator('button[type=submit]').click();
	await expect(page).toHaveURL('/');
	await expect(page.locator('main').getByText(`Welcome back, ${displayName}`)).toBeVisible();

	// ---- /settings/security → change password ------------------------------
	await page.getByRole('link', { name: /Security settings/i }).click();
	await expect(page).toHaveURL(/\/settings\/security$/);

	await page.locator('#currentPassword').fill(password);
	await page.locator('#newPassword').fill(newPassword);
	await page.locator('#confirmPassword').fill(newPassword);
	const changeResponse = page.waitForResponse(
		(r) => r.url().includes('/api/v1/auth/password/change') && r.request().method() === 'POST'
	);
	await page.getByRole('button', { name: /^Change password$/ }).click();
	const changed = await changeResponse;
	expect(changed.status()).toBe(200);

	// ---- logout -----------------------------------------------------------
	await gotoHydrated(page, '/');
	await page.getByRole('button', { name: /^Sign out$/ }).click();
	await expect(page).toHaveURL(/\/login/);

	// ---- old password no longer works (status only — don't trip backoff) ---
	await page.locator('#email').fill(email);
	await page.locator('#password').fill(password);
	await page.locator('button[type=submit]').click();
	await expect(page.getByText('Email or password is incorrect.')).toBeVisible();

	// ---- new password works ----------------------------------------------
	// Wait out the 2-second backoff window from the failed attempt above.
	await page.waitForTimeout(2_500);
	await page.locator('#password').fill(newPassword);
	await page.locator('button[type=submit]').click();
	await expect(page).toHaveURL('/');
	await expect(page.locator('main').getByText(`Welcome back, ${displayName}`)).toBeVisible();
});
