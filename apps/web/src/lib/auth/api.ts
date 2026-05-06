import type { Schemas } from '../api';
import { readCsrfToken, withCsrfHeader } from './csrf';

const SAFE_METHODS = new Set(['GET', 'HEAD', 'OPTIONS']);

function isSafeMethod(method: string): boolean {
	return SAFE_METHODS.has(method.toUpperCase());
}

function hasCsrfCookie(): boolean {
	return readCsrfToken() !== null;
}

/* -----------------------------------------------------------------------------
 * Wire types — generated from the backend's OpenAPI spec at /v3/api-docs and
 * re-exported under the names existing call sites already use. The
 * generator runs on demand via `pnpm gen:api`; it's not in the build
 * pipeline because that would couple `pnpm build` to a running backend.
 * -------------------------------------------------------------------------- */

export type ApiUser = Schemas['UserResponse'];
export type ApiMessage = Schemas['MessageResponse'];

/**
 * Error envelope returned by every endpoint that surfaces a friendly
 * code. springdoc renders `ResponseEntity<*>` as a generic shape, so this
 * one is hand-written — it matches `com.kliniq.api.error.ApiErrorResponse`
 * on the backend.
 */
export interface ApiError {
	code: string;
	message: string;
	fieldErrors?: Array<{ field: string; message: string }>;
}

/** Thrown by `api*` helpers when the response is non-2xx. Carries the parsed envelope. */
export class ApiError_ extends Error {
	constructor(
		public readonly status: number,
		public readonly payload: ApiError
	) {
		super(payload.message);
		this.name = 'ApiError';
	}
}

export interface JsonInit extends Omit<RequestInit, 'body'> {
	body?: unknown;
}

/**
 * Shared JSON request helper used by every `*Api` object in this folder.
 * Handles cookies, CSRF header echo, and the ApiError_ envelope.
 */
export async function apiRequest<T>(method: string, path: string, init: JsonInit = {}): Promise<T> {
	// State-changing requests need an X-XSRF-TOKEN echo of the cookie.
	// (auth)/+layout calls primeCsrf() in onMount but doesn't await it —
	// if the user (or a Playwright test) submits a form before that GET
	// finishes, we'd POST without a cookie and the backend would 403.
	// Lazily seed the cookie here when we notice it's missing.
	if (!isSafeMethod(method) && !hasCsrfCookie()) {
		try {
			await fetch('/api/v1/auth/me', { credentials: 'include' });
		} catch {
			// If /me itself is unreachable the original request will fail
			// next, with a more useful error envelope.
		}
	}

	const headers = withCsrfHeader(init.headers);
	headers.set('Content-Type', 'application/json');
	headers.set('Accept', 'application/json');

	const res = await fetch(path, {
		...init,
		method,
		credentials: 'include', // cookies (session + xsrf) must travel
		headers,
		body: init.body !== undefined ? JSON.stringify(init.body) : undefined
	});

	if (res.status === 204) {
		return undefined as unknown as T;
	}

	const text = await res.text();
	const parsed: unknown = text ? JSON.parse(text) : null;

	if (!res.ok) {
		const errorPayload =
			parsed && typeof parsed === 'object' && 'code' in parsed
				? (parsed as ApiError)
				: { code: 'UNKNOWN', message: `HTTP ${res.status}` };
		throw new ApiError_(res.status, errorPayload);
	}

	return parsed as T;
}

/* -----------------------------------------------------------------------------
 * Endpoint helpers
 * -------------------------------------------------------------------------- */

export const authApi = {
	register(input: Schemas['RegisterRequest']) {
		return apiRequest<ApiMessage>('POST', '/api/v1/auth/register', { body: input });
	},

	verify(input: Schemas['VerifyRequest']) {
		return apiRequest<ApiMessage>('POST', '/api/v1/auth/verify', { body: input });
	},

	login(input: Schemas['LoginRequest']) {
		return apiRequest<ApiUser>('POST', '/api/v1/auth/login', { body: input });
	},

	logout() {
		return apiRequest<ApiMessage>('POST', '/api/v1/auth/logout', {});
	},

	me() {
		return apiRequest<ApiUser>('GET', '/api/v1/auth/me', {});
	},

	forgotPassword(input: Schemas['ForgotPasswordRequest']) {
		return apiRequest<ApiMessage>('POST', '/api/v1/auth/password/forgot', { body: input });
	},

	resetPassword(input: Schemas['ResetPasswordRequest']) {
		return apiRequest<ApiMessage>('POST', '/api/v1/auth/password/reset', { body: input });
	},

	changePassword(input: Schemas['ChangePasswordRequest']) {
		return apiRequest<ApiMessage>('POST', '/api/v1/auth/password/change', { body: input });
	},

	/**
	 * Round-trip a GET so Spring sets the `XSRF-TOKEN` cookie before the
	 * SPA fires its first POST. Callers usually invoke this once on app load.
	 */
	async primeCsrf() {
		try {
			await apiRequest('GET', '/api/v1/auth/me', {});
		} catch (e) {
			// 401 is fine — we just wanted the cookie; an authentic-or-not check
			// is the side-effect of the GET, not the goal.
			if (e instanceof ApiError_ && e.status === 401) return;
			throw e;
		}
	}
};
