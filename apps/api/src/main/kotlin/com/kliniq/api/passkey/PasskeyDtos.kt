package com.kliniq.api.passkey

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.OffsetDateTime
import java.util.UUID

// =============================================================================
// Wire shapes for the passkey ceremonies. ArrayBuffer fields (challenge,
// credential id, attestation object, signature, etc.) are base64url-encoded
// without padding so they round-trip cleanly with @simplewebauthn/browser on
// the SPA side. Field names match the WebAuthn JSON spec where applicable.
// =============================================================================

// ---- Begin registration -----------------------------------------------------

/** No request body — the begin endpoint relies on the authenticated session. */

data class BeginRegistrationResponse(
    val challenge: String,
    val rp: Rp,
    val user: UserInfo,
    val pubKeyCredParams: List<PubKeyCredParam>,
    val timeout: Long,
    val excludeCredentials: List<DescriptorRef>,
    val authenticatorSelection: AuthenticatorSelection,
    val attestation: String,
)

data class Rp(
    val id: String,
    val name: String,
)

data class UserInfo(
    /** base64url of the WebAuthn user handle (16 bytes derived from the user UUID). */
    val id: String,
    val name: String,
    val displayName: String,
)

data class PubKeyCredParam(
    val type: String,
    val alg: Int,
)

data class DescriptorRef(
    val type: String,
    /** base64url credential id. */
    val id: String,
    val transports: List<String>? = null,
)

data class AuthenticatorSelection(
    val residentKey: String,
    val userVerification: String,
)

// ---- Finish registration ----------------------------------------------------

data class FinishRegistrationRequest(
    /** base64url credential id (the same value returned in `rawId`). */
    @field:NotBlank
    @field:Size(max = MAX_B64URL)
    val id: String,
    /** base64url of the AuthenticatorAttestationResponse.attestationObject. */
    @field:NotBlank
    @field:Size(max = MAX_LONG_B64URL)
    val attestationObject: String,
    /** base64url of the AuthenticatorAttestationResponse.clientDataJSON. */
    @field:NotBlank
    @field:Size(max = MAX_B64URL)
    val clientDataJSON: String,
    /** Authenticator transport hints from getTransports(). Optional. */
    val transports: List<String>? = null,
    /** Human-readable label the user chose for this credential. */
    @field:NotBlank
    @field:Size(min = 1, max = MAX_DEVICE_NAME)
    val deviceName: String,
) {
    companion object {
        const val MAX_DEVICE_NAME = 100
        const val MAX_B64URL = 4096
        const val MAX_LONG_B64URL = 32768
    }
}

// ---- Begin authentication ---------------------------------------------------

data class BeginAuthenticationRequest(
    /**
     * Optional. Username-first flow supplies the email so we can return an
     * allowCredentials list (and surface "no passkeys for this account"
     * before the browser prompt). Discoverable-credential flow leaves this
     * blank and the browser picks one of its stored passkeys.
     */
    @field:Size(max = MAX_EMAIL)
    val email: String? = null,
) {
    companion object {
        const val MAX_EMAIL = 254
    }
}

data class BeginAuthenticationResponse(
    /** Opaque handle the SPA must send back on /finish. */
    val handle: String,
    val challenge: String,
    val rpId: String,
    val timeout: Long,
    val userVerification: String,
    val allowCredentials: List<DescriptorRef>,
)

// ---- Finish authentication --------------------------------------------------

data class FinishAuthenticationRequest(
    /** Handle issued by /begin. */
    @field:NotBlank
    @field:Size(max = FinishRegistrationRequest.MAX_B64URL)
    val handle: String,
    @field:NotBlank
    @field:Size(max = FinishRegistrationRequest.MAX_B64URL)
    val id: String,
    @field:NotBlank
    @field:Size(max = FinishRegistrationRequest.MAX_LONG_B64URL)
    val authenticatorData: String,
    @field:NotBlank
    @field:Size(max = FinishRegistrationRequest.MAX_B64URL)
    val clientDataJSON: String,
    @field:NotBlank
    @field:Size(max = FinishRegistrationRequest.MAX_B64URL)
    val signature: String,
    /** base64url of the user handle the authenticator returned (may be empty). */
    val userHandle: String? = null,
)

// ---- Management -------------------------------------------------------------

data class PasskeySummary(
    val id: UUID,
    val deviceName: String,
    val createdAt: OffsetDateTime,
    val lastUsedAt: OffsetDateTime?,
)

data class RenamePasskeyRequest(
    @field:NotBlank
    @field:Size(min = 1, max = FinishRegistrationRequest.MAX_DEVICE_NAME)
    val deviceName: String,
)
