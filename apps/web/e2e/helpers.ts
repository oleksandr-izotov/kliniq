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

/**
 * Navigate to a SvelteKit route and wait until the JS bundle has finished
 * hydrating. Without this Playwright can win the race and click a form
 * submit button before Svelte has bound its onsubmit handler — in which
 * case the browser does the default-action POST instead, the SPA
 * never sees the response, and the test sees no UI update.
 */
import type { Page } from '@playwright/test';

export async function gotoHydrated(page: Page, path: string): Promise<void> {
	await page.goto(path);
	await page.waitForLoadState('networkidle');
}
