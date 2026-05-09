import { expect, test } from '@playwright/test';

import {
	cleanupTestData,
	fetchVerifyToken,
	gotoHydrated,
	promoteUser,
	uniqueEmail
} from './helpers';

/**
 * End-to-end coverage for Day 25's booking surface: the schedule day-view,
 * the booking-form dialog, and the conflict UX. The test drives a single
 * user that is both a MANAGER (so they can add the OR) and a flagged
 * surgeon (so they show up in the dialog's surgeon picker). One user is
 * enough — STAFF role gating already has unit/integration coverage on
 * the backend.
 */

test.beforeAll(async () => {
	await cleanupTestData();
});

test('manager can create a booking, hit a conflict, and edit the time', async ({ page }) => {
	const email = uniqueEmail('booking-e2e');
	const password = 'first long valid passphrase 99';
	const displayName = 'Dr Schedule';

	// ---- register + verify --------------------------------------------------
	await gotoHydrated(page, '/register');
	await page.locator('#email').fill(email);
	await page.locator('#displayName').fill(displayName);
	await page.locator('#password').fill(password);
	await page.locator('button[type=submit]').click();
	await expect(page.getByText('Check your inbox')).toBeVisible();

	const verifyToken = await fetchVerifyToken(email);
	await gotoHydrated(page, `/verify?token=${verifyToken}`);
	await expect(page.getByText('Email verified')).toBeVisible();

	// Promote: MANAGER (creates ORs) + surgeon (appears in the picker)
	await promoteUser(email, { role: 'MANAGER', surgeonSpecialty: 'GENERAL' });

	// ---- login --------------------------------------------------------------
	await gotoHydrated(page, '/login');
	await page.locator('#email').fill(email);
	await page.locator('#password').fill(password);
	await page.locator('button[type=submit]').click();
	await expect(page).toHaveURL('/');
	await expect(page.locator('main').getByText(`Welcome back, ${displayName}`)).toBeVisible();

	// Home dashboard surfaces role-appropriate tiles. STAFF (default) sees
	// schedule + security; promoting to MANAGER unlocks operating-rooms;
	// ADMIN unlocks clinic settings. We're MANAGER here, so:
	await expect(page.getByTestId('tile-schedule')).toBeVisible();
	await expect(page.getByTestId('tile-operating-rooms')).toBeVisible();
	await expect(page.getByTestId('tile-security')).toBeVisible();
	await expect(page.getByTestId('tile-clinic-settings')).toHaveCount(0);

	// ---- create an OR via the management page ------------------------------
	const orCode = `E2E-${Date.now().toString().slice(-5)}`;
	await gotoHydrated(page, '/operating-rooms');
	await page.locator('#code').fill(orCode);
	await page.locator('#name').fill('E2E Suite');
	await page.getByRole('button', { name: /^Add room$/ }).click();
	await expect(page.getByText(`Added "${orCode}".`)).toBeVisible();

	// ---- /schedule: dialog opens with prefilled OR + time -------------------
	await gotoHydrated(page, '/schedule');
	const orColumn = page.locator(`button[aria-label="Create booking in ${orCode}"]`).or(
		// The column outer is a div in the current implementation; both work.
		page.locator(`div[aria-label="Create booking in ${orCode}"]`)
	);
	await expect(orColumn).toBeVisible();

	// Click somewhere mid-grid to spawn a create dialog. The y offset
	// determines the snapped time; we don't assert on the exact slot —
	// just that the dialog opened with our OR pre-selected.
	await orColumn.click({ position: { x: 50, y: 90 } });

	const dialog = page.locator('dialog[open]');
	await expect(dialog).toBeVisible();
	await expect(dialog.getByText('New booking')).toBeVisible();

	// The OR <select> is bound — confirm the option matching the new OR
	// code is the chosen one.
	await expect(dialog.locator('#bf-or')).toContainText(orCode);

	// Fill in the rest. Pin the times explicitly so we can later assert
	// against the rendered booking block.
	await dialog.locator('#bf-start').fill('09:00');
	await dialog.locator('#bf-end').fill('10:00');
	await dialog.locator('#bf-optype').fill('Knee arthroscopy');
	const patientRef = `P-9999-${Date.now().toString().slice(-3)}`;
	await dialog.locator('#bf-patient').fill(patientRef);

	const created = page.waitForResponse(
		(r) => r.url().endsWith('/api/v1/bookings') && r.request().method() === 'POST'
	);
	await dialog.getByRole('button', { name: /^Create booking$/ }).click();
	const createdResp = await created;
	expect(createdResp.status()).toBe(201);

	await expect(dialog).toBeHidden();
	await expect(page.getByText('Booking created.')).toBeVisible();

	// ---- block renders with the right time label --------------------------
	const block = page
		.locator(`div[aria-label="Create booking in ${orCode}"]`)
		.locator('button', { hasText: '09:00–10:00' });
	await expect(block).toBeVisible();

	// ---- conflict UX: open another create dialog (clicking past the
	// existing 09:00 block so we don't accidentally enter edit mode), then
	// type a time that overlaps the existing one; expect 409.
	await orColumn.click({ position: { x: 50, y: 300 } });
	await expect(dialog).toBeVisible();
	await expect(dialog.getByText('New booking')).toBeVisible();
	await dialog.locator('#bf-start').fill('09:30');
	await dialog.locator('#bf-end').fill('10:30');
	await dialog.locator('#bf-optype').fill('Conflicting op');
	await dialog.locator('#bf-patient').fill(`P-9999-${Date.now().toString().slice(-3)}`);

	const conflict = page.waitForResponse(
		(r) => r.url().endsWith('/api/v1/bookings') && r.request().method() === 'POST'
	);
	await dialog.getByRole('button', { name: /^Create booking$/ }).click();
	const conflictResp = await conflict;
	expect(conflictResp.status()).toBe(409);
	await expect(dialog.getByText('Time conflict')).toBeVisible();

	// Close that dialog without saving.
	await dialog.getByRole('button', { name: /^Cancel$/ }).click();
	await expect(dialog).toBeHidden();

	// ---- side panel: clicking the block opens it (no longer the dialog) -----
	await block.click();
	const panel = page.getByRole('complementary', { name: 'Booking details' });
	await expect(panel).toBeVisible();
	await expect(panel.getByText('scheduled', { exact: true })).toBeVisible();
	await expect(panel.getByText('Knee arthroscopy')).toBeVisible();

	// ---- edit via panel: shift the block to 11:00–12:00 ---------------------
	await panel.getByRole('button', { name: /^Edit$/ }).click();
	await expect(dialog).toBeVisible();
	await expect(dialog.getByText('Edit booking')).toBeVisible();
	await expect(dialog.locator('#bf-patient')).toBeDisabled();

	await dialog.locator('#bf-start').fill('11:00');
	await dialog.locator('#bf-end').fill('12:00');

	const updated = page.waitForResponse(
		(r) => /\/api\/v1\/bookings\/[0-9a-f-]+$/.test(r.url()) && r.request().method() === 'PATCH'
	);
	await dialog.getByRole('button', { name: /^Save changes$/ }).click();
	const updatedResp = await updated;
	expect(updatedResp.status()).toBe(200);

	await expect(dialog).toBeHidden();
	await expect(page.getByText('Booking updated.')).toBeVisible();

	const movedBlock = page
		.locator(`div[aria-label="Create booking in ${orCode}"]`)
		.locator('button', { hasText: '11:00–12:00' });
	await expect(movedBlock).toBeVisible();

	// The original 09:00 block should no longer be on the grid.
	await expect(
		page
			.locator(`div[aria-label="Create booking in ${orCode}"]`)
			.locator('button', { hasText: '09:00–10:00' })
	).toHaveCount(0);

	// ---- cancel via panel: status flips to CANCELLED ------------------------
	page.on('dialog', (d) => d.accept()); // confirm() → OK
	await panel.getByRole('button', { name: /^Cancel$/ }).click();
	await expect(page.getByText('Booking cancelled.')).toBeVisible();
	// Match the status pill (exact text) — the "no actions left" hint also
	// contains the word "cancelled" and would trip strict-mode otherwise.
	await expect(panel.getByText('cancelled', { exact: true })).toBeVisible();
	await expect(panel.getByText(/no actions left/)).toBeVisible();

	// ---- week view: same booking shows up under today's column ------------
	// Toggle to Week from the header — the side panel can stay open; the
	// view-toggle button isn't behind it.
	await page.getByRole('button', { name: /^Week$/ }).click();
	// First day in the default week-of grid is today (test starts with
	// weekFrom=today). The cancelled 11:00–12:00 block should appear in
	// today's column — find that column by data-testid prefix and assert
	// the block is inside it.
	const todayCol = page.locator('[data-testid^="week-col-"]').first();
	await expect(todayCol).toBeVisible();
	await expect(todayCol.locator('button', { hasText: '11:00–12:00' })).toBeVisible({
		timeout: 5_000
	});

	// Prev-week navigation removes today's column out of view; the booking
	// shouldn't appear in the previous week.
	await page.getByRole('button', { name: /^← Prev$/ }).click();
	await expect(
		page.locator('[data-testid^="week-col-"]').first().locator('button', { hasText: '11:00–12:00' })
	).toHaveCount(0);
});
