package com.kliniq.usecase.passkey

import com.github.f4b6a3.uuid.UuidCreator
import com.kliniq.api.passkey.AuthenticatorSelection
import com.kliniq.api.passkey.BeginRegistrationResponse
import com.kliniq.api.passkey.DescriptorRef
import com.kliniq.api.passkey.FinishRegistrationRequest
import com.kliniq.api.passkey.PubKeyCredParam
import com.kliniq.api.passkey.Rp
import com.kliniq.api.passkey.UserInfo
import com.kliniq.domain.passkey.NewPasskey
import com.kliniq.domain.passkey.Passkey
import com.kliniq.domain.user.User
import com.kliniq.infra.audit.AuditEntry
import com.kliniq.infra.audit.AuditWriter
import com.kliniq.infra.webauthn.PasskeyChallengeStore
import com.kliniq.infra.webauthn.WebAuthnProperties
import com.kliniq.persistence.passkey.PasskeyRepository
import com.webauthn4j.WebAuthnManager
import com.webauthn4j.converter.AttestedCredentialDataConverter
import com.webauthn4j.data.PublicKeyCredentialParameters
import com.webauthn4j.data.PublicKeyCredentialType
import com.webauthn4j.data.RegistrationParameters
import com.webauthn4j.data.RegistrationRequest
import com.webauthn4j.data.attestation.statement.COSEAlgorithmIdentifier
import com.webauthn4j.data.client.Origin
import com.webauthn4j.data.client.challenge.DefaultChallenge
import com.webauthn4j.server.ServerProperty
import com.webauthn4j.util.exception.WebAuthnException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

/**
 * The registration ceremony: a logged-in user provisions a new credential.
 *
 *   begin()  → server generates a challenge, returns the
 *              `PublicKeyCredentialCreationOptions` blob, parks the challenge
 *              in Redis under the user id.
 *   finish() → SPA hands the attestation back. We look the challenge up,
 *              validate the attestation via WebAuthn4J, then persist the
 *              new credential.
 *
 * "Attestation" is set to `none`: the server doesn't care which authenticator
 * model you used, only that the resulting public key is bound to the
 * challenge. This is the default consumer-passkey posture.
 */
@Service
@Suppress("LongParameterList") // ceremony coordinator pulls in the obvious neighbours
class RegisterPasskeyUseCase(
    private val webAuthnManager: WebAuthnManager,
    private val attestedCredentialDataConverter: AttestedCredentialDataConverter,
    private val challengeStore: PasskeyChallengeStore,
    private val passkeys: PasskeyRepository,
    private val auditWriter: AuditWriter,
    private val properties: WebAuthnProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val rng = SecureRandom()

    fun begin(user: User): BeginRegistrationResponse {
        val challenge = ByteArray(CHALLENGE_BYTES).also(rng::nextBytes)
        challengeStore.storeRegistration(user.id, challenge)

        val existing = passkeys.findByUserId(user.id)
        log.info("passkey.begin: user={} existingCount={}", user.id, existing.size)

        return BeginRegistrationResponse(
            challenge = b64url(challenge),
            rp = Rp(id = properties.rpId, name = properties.rpName),
            user =
                UserInfo(
                    id = b64url(userHandle(user.id)),
                    name = user.email,
                    displayName = user.displayName,
                ),
            pubKeyCredParams = SUPPORTED_ALGORITHMS,
            timeout = properties.ceremonyTimeout.toMillis(),
            excludeCredentials = existing.map { DescriptorRef(type = "public-key", id = b64url(it.credentialId)) },
            authenticatorSelection =
                AuthenticatorSelection(
                    residentKey = "preferred",
                    userVerification = "preferred",
                ),
            attestation = "none",
        )
    }

    @Suppress("ReturnCount", "LongMethod") // ceremony coordinator with early-return guards
    fun finish(
        user: User,
        request: FinishRegistrationRequest,
    ): Result {
        val challenge = challengeStore.consumeRegistration(user.id) ?: return Result.MissingChallenge

        val regRequest =
            RegistrationRequest(
                Base64.getUrlDecoder().decode(request.attestationObject),
                Base64.getUrlDecoder().decode(request.clientDataJSON),
            )

        val regParameters =
            RegistrationParameters(
                buildServerProperty(challenge),
                pubKeyCredParams(),
                // userVerificationRequired =
                false,
                // userPresenceRequired =
                true,
            )

        val regData =
            try {
                webAuthnManager.validate(regRequest, regParameters)
            } catch (
                @Suppress("TooGenericExceptionCaught") e: WebAuthnException,
            ) {
                log.warn("passkey.finish: attestation validation failed for user {}: {}", user.id, e.message)
                return Result.InvalidAttestation(e.message ?: "validation failed")
            }

        val authData =
            regData.attestationObject?.authenticatorData
                ?: return Result.InvalidAttestation("authenticatorData missing")
        val attestedCredentialData =
            authData.attestedCredentialData
                ?: return Result.InvalidAttestation("attestedCredentialData missing")

        val credentialIdBytes = attestedCredentialData.credentialId
        // Ignore replays of the same credential by the same user — UNIQUE on
        // credential_id will reject it at the DB level too, but a friendly
        // result keeps the audit trail clean.
        if (passkeys.findByCredentialId(credentialIdBytes) != null) {
            return Result.AlreadyRegistered
        }

        val acdBytes = attestedCredentialDataConverter.convert(attestedCredentialData)
        val aaguidUuid = attestedCredentialData.aaguid?.value
        val signCount = authData.signCount

        val passkey =
            passkeys.create(
                NewPasskey(
                    id = UuidCreator.getTimeOrderedEpoch(),
                    userId = user.id,
                    credentialId = credentialIdBytes,
                    publicKey = acdBytes,
                    signatureCounter = signCount.toLong(),
                    aaguid = aaguidUuid,
                    deviceName = request.deviceName.trim(),
                ),
            )

        auditWriter.record(
            AuditEntry(
                action = "passkey.registered",
                entityType = "passkey",
                entityId = passkey.id,
                actorUserId = user.id,
                metadata = mapOf("aaguid" to aaguidUuid?.toString(), "deviceName" to passkey.deviceName),
            ),
        )
        log.info("passkey.finish: user={} passkey={}", user.id, passkey.id)
        return Result.Success(passkey)
    }

    private fun buildServerProperty(challenge: ByteArray): ServerProperty {
        val origins = properties.origins.map { Origin.create(it) }.toSet()
        return ServerProperty(origins, properties.rpId, DefaultChallenge(challenge), null)
    }

    sealed interface Result {
        data class Success(
            val passkey: Passkey,
        ) : Result

        /** /finish was called but no challenge is in flight (timeout, replay, or wrong user). */
        data object MissingChallenge : Result

        /** WebAuthn4J rejected the attestation. [reason] is a debug hint, not user-facing copy. */
        data class InvalidAttestation(
            val reason: String,
        ) : Result

        /** This raw credential id is already registered. */
        data object AlreadyRegistered : Result
    }

    companion object {
        private const val CHALLENGE_BYTES = 32

        private val SUPPORTED_ALGORITHMS =
            listOf(
                // ES256 — the default for most platform authenticators (TouchID, Windows Hello).
                PubKeyCredParam(type = "public-key", alg = COSE_ES256),
                // RS256 — older Windows Hello / cross-device authenticators.
                PubKeyCredParam(type = "public-key", alg = COSE_RS256),
            )
        private const val COSE_ES256 = -7
        private const val COSE_RS256 = -257

        private val ENCODER = Base64.getUrlEncoder().withoutPadding()

        private fun b64url(bytes: ByteArray): String = ENCODER.encodeToString(bytes)

        /**
         * 16-byte stable user handle derived from the user UUID. WebAuthn
         * requires the handle to be opaque-to-the-RP-and-the-server-from-
         * the-authenticator's-perspective; using the UUID bytes is fine
         * because the user id is already opaque on our side.
         */
        private fun userHandle(userId: UUID): ByteArray {
            val buf = ByteBuffer.allocate(USER_HANDLE_BYTES)
            buf.putLong(userId.mostSignificantBits)
            buf.putLong(userId.leastSignificantBits)
            return buf.array()
        }

        private const val USER_HANDLE_BYTES = 16

        private fun pubKeyCredParams(): List<PublicKeyCredentialParameters> =
            listOf(
                PublicKeyCredentialParameters(PublicKeyCredentialType.PUBLIC_KEY, COSEAlgorithmIdentifier.ES256),
                PublicKeyCredentialParameters(PublicKeyCredentialType.PUBLIC_KEY, COSEAlgorithmIdentifier.RS256),
            )
    }
}
