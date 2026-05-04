/**
 * CSRF helpers for the Spring Security `CookieCsrfTokenRepository`
 * (double-submit cookie pattern).
 *
 *   - The backend sets the `XSRF-TOKEN` cookie on any request.
 *   - On every state-changing request (POST / PUT / PATCH / DELETE) we read
 *     it back from `document.cookie` and echo it as `X-XSRF-TOKEN`.
 *   - Spring's filter compares cookie vs. header; a cross-site request can
 *     forge the cookie but not the header, so it gets a 403.
 */

const COOKIE_NAME = 'XSRF-TOKEN';
const HEADER_NAME = 'X-XSRF-TOKEN';

export function readCsrfToken(): string | null {
	if (typeof document === 'undefined') return null;
	const match = document.cookie
		.split(';')
		.map((part) => part.trim())
		.find((part) => part.startsWith(`${COOKIE_NAME}=`));
	if (!match) return null;
	return decodeURIComponent(match.slice(COOKIE_NAME.length + 1));
}

/**
 * Build a fresh `Headers` object with the CSRF header attached when one is
 * available. Always safe to call — pages that haven't received the cookie
 * yet will end up sending the request without the header, and the backend
 * will respond with 403; the caller can then trigger a GET to seed the
 * cookie and retry.
 */
export function withCsrfHeader(init?: HeadersInit): Headers {
	const headers = new Headers(init);
	const token = readCsrfToken();
	if (token && !headers.has(HEADER_NAME)) {
		headers.set(HEADER_NAME, token);
	}
	return headers;
}
