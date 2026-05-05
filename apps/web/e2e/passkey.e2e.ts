import { expect, test } from '@playwright/test';

import { fetchVerifyToken, gotoHydrated, uniqueEmail } from './helpers';

/**
 * Drives the full passkey UX against Chromium's CDP-backed virtual
 * authenticator: register a fresh account, add a passkey from the
 * settings page, sign out, sign back in via "Sign in with a passkey".
 */
test('register a passkey and sign back in with it via the virtual authenticator', async ({
	page,
	context
}) => {
	const email = uniqueEmail('pk');
	const password = 'tmp long valid passphrase 99';
	const displayName = 'PK Tester';

	// Spin up the account — verify via the email link, then password-login.
	await gotoHydrated(page, '/register');
	await page.locator('#email').fill(email);
	await page.locator('#displayName').fill(displayName);
	await page.locator('#password').fill(password);
	await page.locator('button[type=submit]').click();
	await expect(page.getByText('Check your inbox')).toBeVisible();

	const verifyToken = await fetchVerifyToken(email);
	await gotoHydrated(page, `/verify?token=${verifyToken}`);
	await expect(page.getByText('Email verified')).toBeVisible();

	await gotoHydrated(page, '/login');
	await page.locator('#email').fill(email);
	await page.locator('#password').fill(password);
	await page.locator('button[type=submit]').click();
	await expect(page).toHaveURL('/');

	// Attach a virtual platform authenticator. The browser will route every
	// navigator.credentials.create / .get against this in-memory key,
	// no biometric prompt required.
	const cdp = await context.newCDPSession(page);
	await cdp.send('WebAuthn.enable');
	await cdp.send('WebAuthn.addVirtualAuthenticator', {
		options: {
			protocol: 'ctap2',
			transport: 'internal',
			hasResidentKey: true,
			hasUserVerification: true,
			isUserVerified: true,
			automaticPresenceSimulation: true
		}
	});

	// Add a passkey from /settings/security.
	await gotoHydrated(page, '/settings/security');
	await page.locator('#deviceName').fill('Virtual TPM');
	await page.getByRole('button', { name: /^Add passkey$/ }).click();
	// Scope to the passkey list so the toast banner doesn't double-match.
	await expect(page.locator('main li').filter({ hasText: 'Virtual TPM' })).toBeVisible();

	// Sign out.
	await gotoHydrated(page, '/');
	await page.getByRole('button', { name: /^Sign out$/ }).click();
	await expect(page).toHaveURL(/\/login/);

	// Sign in with the passkey — supply the email so the server returns
	// allowCredentials and the virtual authenticator picks the right key.
	await page.locator('#email').fill(email);
	await page.getByRole('button', { name: /Sign in with a passkey/i }).click();
	await expect(page).toHaveURL('/');
	await expect(page.locator('main').getByText(`Welcome back, ${displayName}`)).toBeVisible();
});
