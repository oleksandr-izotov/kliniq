import {
	browserSupportsWebAuthn,
	platformAuthenticatorIsAvailable,
	startAuthentication,
	startRegistration,
	type PublicKeyCredentialCreationOptionsJSON,
	type PublicKeyCredentialRequestOptionsJSON
} from '@simplewebauthn/browser';

import { apiRequest, ApiError_, type ApiUser } from './api';

/* -----------------------------------------------------------------------------
 * Wire types — match `PasskeyDtos.kt` on the API side. ArrayBuffer-shaped
 * fields are base64url strings, everything else is plain JSON.
 * -------------------------------------------------------------------------- */

export interface PasskeySummary {
	id: string;
	deviceName: string;
	createdAt: string;
	lastUsedAt: string | null;
}

/**
 * Begin-registration response. Adds nothing on top of @simplewebauthn's
 * shape — the field names map 1:1, so we can hand it straight to
 * startRegistration().
 */
type BeginRegistrationResponse = PublicKeyCredentialCreationOptionsJSON;

/**
 * Begin-authentication response. Same as @simplewebauthn's request options
 * plus an opaque `handle` the SPA echoes back on /finish so the server can
 * find the in-flight challenge. simplewebauthn ignores unknown fields.
 */
type BeginAuthenticationResponse = PublicKeyCredentialRequestOptionsJSON & {
	handle: string;
};

/* -----------------------------------------------------------------------------
 * Errors
 * -------------------------------------------------------------------------- */

/**
 * Thrown when the browser ceremony itself fails — user cancels the prompt,
 * the authenticator times out, the platform refuses, etc. Distinct from
 * [ApiError_] so callers can show "no passkey selected" vs "server error".
 */
export class PasskeyCeremonyError extends Error {
	constructor(
		public readonly cause: unknown,
		message: string
	) {
		super(message);
		this.name = 'PasskeyCeremonyError';
	}
}

/* -----------------------------------------------------------------------------
 * Capability probe — call once on page load to decide whether to show the
 * passkey UI at all.
 * -------------------------------------------------------------------------- */

export const passkeysSupported = (): boolean => browserSupportsWebAuthn();

export const platformPasskeysAvailable = async (): Promise<boolean> => {
	try {
		return await platformAuthenticatorIsAvailable();
	} catch {
		return false;
	}
};

/* -----------------------------------------------------------------------------
 * Endpoint helpers
 * -------------------------------------------------------------------------- */

export const passkeyApi = {
	/**
	 * Drive the registration ceremony for the *currently logged-in* user.
	 * Throws PasskeyCeremonyError if the browser prompt fails, ApiError_
	 * if the server rejects the attestation.
	 */
	async register(deviceName: string): Promise<PasskeySummary> {
		const options = await apiRequest<BeginRegistrationResponse>(
			'POST',
			'/api/v1/auth/passkeys/registration/begin',
			{}
		);

		let attestation;
		try {
			attestation = await startRegistration({ optionsJSON: options });
		} catch (e) {
			throw new PasskeyCeremonyError(e, ceremonyErrorMessage(e, 'register'));
		}

		return apiRequest<PasskeySummary>('POST', '/api/v1/auth/passkeys/registration/finish', {
			body: {
				id: attestation.id,
				attestationObject: attestation.response.attestationObject,
				clientDataJSON: attestation.response.clientDataJSON,
				transports: attestation.response.transports,
				deviceName
			}
		});
	},

	/**
	 * Drive the authentication ceremony. `email` is optional: when provided,
	 * the server narrows allowCredentials so the browser shows only that
	 * user's passkeys; when omitted, the browser uses discoverable creds.
	 */
	async signIn(email?: string): Promise<ApiUser> {
		const options = await apiRequest<BeginAuthenticationResponse>(
			'POST',
			'/api/v1/auth/passkeys/authentication/begin',
			{ body: { email } }
		);

		let assertion;
		try {
			assertion = await startAuthentication({ optionsJSON: options });
		} catch (e) {
			throw new PasskeyCeremonyError(e, ceremonyErrorMessage(e, 'signIn'));
		}

		return apiRequest<ApiUser>('POST', '/api/v1/auth/passkeys/authentication/finish', {
			body: {
				handle: options.handle,
				id: assertion.id,
				authenticatorData: assertion.response.authenticatorData,
				clientDataJSON: assertion.response.clientDataJSON,
				signature: assertion.response.signature,
				userHandle: assertion.response.userHandle ?? null
			}
		});
	},

	list(): Promise<PasskeySummary[]> {
		return apiRequest<PasskeySummary[]>('GET', '/api/v1/auth/passkeys', {});
	},

	rename(id: string, deviceName: string): Promise<void> {
		return apiRequest<void>('PATCH', `/api/v1/auth/passkeys/${id}`, {
			body: { deviceName }
		});
	},

	revoke(id: string): Promise<void> {
		return apiRequest<void>('DELETE', `/api/v1/auth/passkeys/${id}`, {});
	}
};

/**
 * Translate a thrown DOMException (or anything else) into a user-facing
 * message. The browser raises `NotAllowedError` for both "cancelled" and
 * "timed out" — we don't try to distinguish them.
 */
function ceremonyErrorMessage(e: unknown, phase: 'register' | 'signIn'): string {
	if (typeof e === 'object' && e !== null && 'name' in e) {
		const name = (e as DOMException).name;
		if (name === 'NotAllowedError') {
			return phase === 'register'
				? 'Passkey creation was cancelled or timed out.'
				: 'Passkey sign-in was cancelled or timed out.';
		}
		if (name === 'InvalidStateError') {
			return 'This authenticator already has a passkey for this site.';
		}
	}
	return phase === 'register' ? 'Could not create a passkey.' : 'Could not sign in with a passkey.';
}

/** Re-export for callers that want a unified `ApiError_ | PasskeyCeremonyError` check. */
export { ApiError_ };
