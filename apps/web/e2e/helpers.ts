/**
 * Shared E2E utilities. The browser-side tests need to read tokens that
 * the backend dispatches via email — Mailpit's HTTP API gives us the
 * inbox, so we poll it for the relevant message and pluck the token out
 * of the link.
 */

const MAILPIT_BASE = 'http://localhost:8025';
const POLL_DEADLINE_MS = 10_000;
const POLL_INTERVAL_MS = 250;

interface MailpitListMessage {
	ID: string;
	Subject: string;
	To?: Array<{ Address?: string }>;
}

interface MailpitMessage {
	HTML?: string;
	Text?: string;
}

interface MailpitListResponse {
	messages?: MailpitListMessage[];
}

export const fetchVerifyToken = (email: string): Promise<string> =>
	fetchTokenFromMailpit(email, 'verify');

export const fetchResetToken = (email: string): Promise<string> =>
	fetchTokenFromMailpit(email, 'reset');

async function fetchTokenFromMailpit(email: string, kind: 'verify' | 'reset'): Promise<string> {
	const deadline = Date.now() + POLL_DEADLINE_MS;
	const re = new RegExp(`${kind}\\?token=([A-Za-z0-9_-]+)`);
	const lower = email.toLowerCase();

	while (Date.now() < deadline) {
		const listRaw = await fetch(`${MAILPIT_BASE}/api/v1/messages?limit=30`);
		const list = (await listRaw.json()) as MailpitListResponse;
		const candidate = (list.messages ?? []).find(
			(m) =>
				m.To?.some((t) => t.Address?.toLowerCase() === lower) &&
				m.Subject?.toLowerCase().includes(kind)
		);
		if (candidate) {
			const fullRaw = await fetch(`${MAILPIT_BASE}/api/v1/message/${candidate.ID}`);
			const full = (await fullRaw.json()) as MailpitMessage;
			const body = `${full.HTML ?? ''} ${full.Text ?? ''}`;
			const match = body.match(re);
			if (match) return match[1];
		}
		await new Promise((r) => setTimeout(r, POLL_INTERVAL_MS));
	}
	throw new Error(`No ${kind} mail for ${email} arrived within ${POLL_DEADLINE_MS}ms`);
}

/** Stable, collision-free email per test run. */
export const uniqueEmail = (prefix: string): string =>
	`${prefix}-${Date.now()}-${Math.random().toString(36).slice(2, 8)}@kliniq.test`;

/* -----------------------------------------------------------------------------
 * Test-only DB seeding. The booking modal needs a surgeon row, and the
 * /operating-rooms page needs a MANAGER session — neither has a public
 * endpoint that can flip those bits, so we reach into the dev Postgres
 * directly. Credentials match `compose.yaml`'s dev defaults.
 * -------------------------------------------------------------------------- */

import { Client } from 'pg';
import { createClient as createRedisClient } from 'redis';

const DEV_PG_URL = 'postgres://kliniq:kliniq_dev_only@localhost:55432/kliniq';
const DEV_REDIS_URL = 'redis://localhost:6379';

async function withClient<T>(fn: (c: Client) => Promise<T>): Promise<T> {
	const c = new Client({ connectionString: DEV_PG_URL });
	await c.connect();
	try {
		return await fn(c);
	} finally {
		await c.end();
	}
}

/**
 * Promote a registered+verified user to MANAGER and (optionally) flag
 * them as a surgeon with a specialty so the booking-modal picker can
 * find them. Idempotent — running twice doesn't matter.
 */
export async function promoteUser(
	email: string,
	opts: { role?: 'STAFF' | 'MANAGER' | 'ADMIN'; surgeonSpecialty?: string } = {}
): Promise<void> {
	const role = opts.role ?? 'MANAGER';
	const isSurgeon = opts.surgeonSpecialty != null;
	const specialty = opts.surgeonSpecialty ?? null;
	await withClient(async (c) => {
		await c.query(
			'UPDATE users SET role = $1, is_surgeon = $2, specialty = $3 WHERE email_normalized = $4',
			[role, isSurgeon, specialty, email.toLowerCase()]
		);
	});
}

/**
 * Drop test artefacts from previous runs so tests stay independent without
 * a full DB reset between them. Targets fixtures created by the e2e suite
 * by email/code prefix; production-shaped rows are left alone.
 *
 * Also clears the per-IP rate-limit counters in Redis. With `workers: 1`
 * the suite registers ~5 fresh users back-to-back in well under a minute,
 * which is exactly the `POST /auth/register` quota — leaving the
 * counters intact between files made the back of the suite flaky.
 */
export async function cleanupTestData(): Promise<void> {
	await withClient(async (c) => {
		await c.query("DELETE FROM bookings WHERE patient_ref LIKE 'P-9999-%'");
		await c.query("DELETE FROM operating_rooms WHERE code LIKE 'E2E-%'");
		await c.query("DELETE FROM user_invitations WHERE email_normalized LIKE '%@kliniq.test'");
		await c.query("DELETE FROM users WHERE email_normalized LIKE '%@kliniq.test'");
	});
	const redis = createRedisClient({ url: DEV_REDIS_URL });
	await redis.connect();
	try {
		// Drop rate-limit counters and login-backoff keys but leave session
		// cookies alone — concurrent dev sessions on the same Redis stay
		// alive while the e2e suite runs.
		for (const pattern of ['rate-limit:*', 'kliniq:login-backoff:*']) {
			const keys = await redis.keys(pattern);
			if (keys.length > 0) await redis.del(keys);
		}
	} finally {
		await redis.quit();
	}
}

/**
 * Navigate to a SvelteKit route and wait until the JS bundle has finished
 * hydrating. Without this Playwright can win the race and click a form
 * submit button before Svelte has bound its onsubmit handler — in which
 * case the browser does the default-action POST instead, the SPA
 * never sees the response, and the test sees no UI update.
 *
 * `'load'` triggers once the document and its bundle have loaded, but
 * Svelte 5 runs hydration on the next tick, so click-then-assert can
 * still race. `mode-watcher` writes `data-theme` on `<html>` during
 * hydration regardless of the resolved theme; using its presence as a
 * "hydration is done" signal avoids both the SSE-blocks-networkidle
 * problem on the authenticated routes and the brittle fixed-sleep
 * fallback.
 */
import type { Page } from '@playwright/test';

export async function gotoHydrated(page: Page, path: string): Promise<void> {
	await page.goto(path);
	await page.waitForLoadState('load');
	await page.waitForFunction(() => document.documentElement.hasAttribute('data-theme'));
}
