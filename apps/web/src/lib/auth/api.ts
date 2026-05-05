import { withCsrfHeader } from './csrf';

/**
 * Wire types for our `/api/v1/auth/*` endpoints. Hand-written for V1; we'll
 * generate them from OpenAPI in a later sprint when the backend exposes a
 * stable spec.
 */

export interface ApiUser {
	id: string;
	email: string;
	displayName: string;
	role: 'ADMIN' | 'MANAGER' | 'STAFF';
	isSurgeon: boolean;
	specialty: 'CARDIOLOGY' | 'ORTHOPEDICS' | 'GENERAL' | 'NEUROSURGERY' | 'OPHTHALMOLOGY' | null;
	status: 'ACTIVE' | 'DISABLED';
	emailVerifiedAt: string | null;
	createdAt: string;
}

export interface ApiMessage {
	message: string;
}

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
	register(input: { email: string; password: string; displayName: string }) {
		return apiRequest<ApiMessage>('POST', '/api/v1/auth/register', { body: input });
	},

	verify(input: { token: string }) {
		return apiRequest<ApiMessage>('POST', '/api/v1/auth/verify', { body: input });
	},

	login(input: { email: string; password: string }) {
		return apiRequest<ApiUser>('POST', '/api/v1/auth/login', { body: input });
	},

	logout() {
		return apiRequest<ApiMessage>('POST', '/api/v1/auth/logout', {});
	},

	me() {
		return apiRequest<ApiUser>('GET', '/api/v1/auth/me', {});
	},

	forgotPassword(input: { email: string }) {
		return apiRequest<ApiMessage>('POST', '/api/v1/auth/password/forgot', { body: input });
	},

	resetPassword(input: { token: string; newPassword: string }) {
		return apiRequest<ApiMessage>('POST', '/api/v1/auth/password/reset', { body: input });
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
