import { dev } from '$app/environment';
import { env } from '$env/dynamic/private';
import { env as publicEnv } from '$env/dynamic/public';
import * as Sentry from '@sentry/sveltekit';
import { handleErrorWithSentry, sentryHandle } from '@sentry/sveltekit';
import { sequence } from '@sveltejs/kit/hooks';
import type { Handle, HandleServerError } from '@sveltejs/kit';

/**
 * Direct backend URL for server-to-server /me lookups. Falls back to the
 * dev default. Setting BACKEND_URL in production points the SvelteKit
 * server at the deployed API.
 *
 * NOTE: we do NOT use event.fetch here — for relative URLs SvelteKit
 * dispatches the call back through this same handle hook, which causes
 * unbounded recursion (handle → event.fetch → handle → …).
 */
const BACKEND_URL = env.BACKEND_URL ?? 'https://localhost:8443';

if (dev) {
	// The dev backend uses a mkcert-issued cert that Node's fetch won't
	// trust by default. Disabling TLS verification only matters here, only
	// in dev — production reads BACKEND_URL with a real chain of trust.
	process.env.NODE_TLS_REJECT_UNAUTHORIZED = '0';
}

// Server-side Sentry init mirrors the client init. The same DSN string is
// shared via $env/dynamic/public because it's a public identifier — the
// "PUBLIC_" prefix marks it safe to leak into the client bundle, not
// secret. `enabled: false` keeps the SDK silent in local dev.
Sentry.init({
	dsn: publicEnv.PUBLIC_SENTRY_DSN,
	enabled: Boolean(publicEnv.PUBLIC_SENTRY_DSN),
	environment: 'prod',
	tracesSampleRate: 0
});

/**
 * Resolve the current user once per request from the backend session
 * cookie. Server load functions and pages then read event.locals.user
 * (and $page.data.user on the client) without each one re-fetching /me.
 */
const meHandle: Handle = async ({ event, resolve }) => {
	const cookieHeader = event.request.headers.get('cookie') ?? '';

	try {
		const res = await fetch(`${BACKEND_URL}/api/v1/auth/me`, {
			headers: { Accept: 'application/json', Cookie: cookieHeader }
		});
		event.locals.user = res.ok ? await res.json() : null;
	} catch {
		// Backend unreachable: degrade to anonymous, let route guards handle it.
		event.locals.user = null;
	}

	return resolve(event);
};

// sentryHandle() must run first so it can wrap downstream handlers in its
// own span and capture any error they throw.
export const handle: Handle = sequence(sentryHandle(), meHandle);

export const handleError: HandleServerError = handleErrorWithSentry();
