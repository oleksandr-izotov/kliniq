import { dev } from '$app/environment';
import { env } from '$env/dynamic/private';
import type { Handle } from '@sveltejs/kit';

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

/**
 * Resolve the current user once per request from the backend session
 * cookie. Server load functions and pages then read event.locals.user
 * (and $page.data.user on the client) without each one re-fetching /me.
 */
export const handle: Handle = async ({ event, resolve }) => {
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
