package com.kliniq.usecase.passkey

import com.kliniq.api.passkey.BeginAuthenticationResponse
import com.kliniq.api.passkey.DescriptorRef
import com.kliniq.api.passkey.FinishAuthenticationRequest
import com.kliniq.domain.auth.Session
import com.kliniq.domain.user.User
import com.kliniq.infra.audit.AuditEntry
import com.kliniq.infra.audit.AuditWriter
import com.kliniq.infra.security.SessionStore
import com.kliniq.infra.webauthn.PasskeyChallengeStore
import com.kliniq.infra.webauthn.WebAuthnProperties
import com.kliniq.persistence.passkey.PasskeyRepository
import com.kliniq.persistence.user.UserRepository
import com.webauthn4j.WebAuthnManager
import com.webauthn4j.authenticator.AuthenticatorImpl
import com.webauthn4j.converter.AttestedCredentialDataConverter
import com.webauthn4j.data.AuthenticationParameters
import com.webauthn4j.data.AuthenticationRequest
import com.webauthn4j.data.attestation.statement.NoneAttestationStatement
import com.webauthn4j.data.client.Origin
import com.webauthn4j.data.client.challenge.DefaultChallenge
import com.webauthn4j.server.ServerProperty
import com.webauthn4j.util.exception.WebAuthnException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.security.SecureRandom
import java.time.Clock
import java.util.Base64

/**
 * The authentication ceremony: an anonymous request proves possession of a
 * registered credential and we hand back a session.
 *
 * Two entry points behave the same way; the only difference is the SPA's
 * UX hint:
 *   - username-first: SPA passes `email`. We pre-resolve the user and put
 *     their credentials in `allowCredentials` so the browser narrows the
 *     prompt and can short-circuit if the user has none.
 *   - discoverable: SPA passes nothing. Browser shows whatever passkeys it
 *     has stored for our RP id. The credential we get back tells us who
 *     signed in.
 *
 * Either way, finish() looks the credential up by its raw id and
 * reconstructs the WebAuthn4J [com.webauthn4j.authenticator.Authenticator]
 * from what we stored at registration.
 */
@Service
@Suppress("LongParameterList") // ceremony coordinator pulls in the obvious neighbours
class AuthenticatePasskeyUseCase(
    private val webAuthnManager: WebAuthnManager,
    private val attestedCredentialDataConverter: AttestedCredentialDataConverter,
    private val challengeStore: PasskeyChallengeStore,
    private val passkeys: PasskeyRepository,
    private val users: UserRepository,
    private val sessions: SessionStore,
    private val auditWriter: AuditWriter,
    private val properties: WebAuthnProperties,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val rng = SecureRandom()

    fun begin(email: String?): BeginAuthenticationResponse {
        val resolved = email?.takeIf { it.isNotBlank() }?.let(users::findByEmail)
        val allow =
            resolved?.let { passkeys.findByUserId(it.id) }?.map { p ->
                DescriptorRef(type = "public-key", id = b64url(p.credentialId))
            } ?: emptyList()

        val challenge = ByteArray(CHALLENGE_BYTES).also(rng::nextBytes)
        val handle = challengeStore.storeAuthentication(challenge, resolved?.id)

        return BeginAuthenticationResponse(
            handle = handle,
            challenge = b64url(challenge),
            rpId = properties.rpId,
            timeout = properties.ceremonyTimeout.toMillis(),
            userVerification = "preferred",
            allowCredentials = allow,
        )
    }

    @Suppress("ReturnCount", "LongMethod") // each rejection branch is one informative line
    fun finish(request: FinishAuthenticationRequest): Result {
        val pending = challengeStore.consumeAuthentication(request.handle) ?: return Result.MissingChallenge

        val rawCredentialId = Base64.getUrlDecoder().decode(request.id)
        val passkey = passkeys.findByCredentialId(rawCredentialId) ?: return Result.UnknownCredential

        // If begin() pre-resolved a user, reject mismatching credentials so
        // a stolen authenticator can't impersonate someone else.
        if (pending.userId != null && pending.userId != passkey.userId) {
            log.warn(
                "passkey.finish: credential owner {} != session-bound user {}",
                passkey.userId,
                pending.userId,
            )
            return Result.UnknownCredential
        }

        val owner = users.findById(passkey.userId) ?: return Result.UnknownCredential
        if (!owner.isActive) return Result.AccountDisabled

        val attestedCredentialData = attestedCredentialDataConverter.convert(passkey.publicKey)
        val authenticator =
            AuthenticatorImpl(
                attestedCredentialData,
                NoneAttestationStatement(),
                passkey.signatureCounter,
            )

        val authRequest =
            AuthenticationRequest(
                rawCredentialId,
                request.userHandle?.takeIf { it.isNotBlank() }?.let(Base64.getUrlDecoder()::decode),
                Base64.getUrlDecoder().decode(request.authenticatorData),
                Base64.getUrlDecoder().decode(request.clientDataJSON),
                Base64.getUrlDecoder().decode(request.signature),
            )

        val authParameters =
            AuthenticationParameters(
                buildServerProperty(pending.challenge),
                authenticator,
                // allowCredentials =
                null,
                // userVerificationRequired =
                false,
                // userPresenceRequired =
                true,
            )

        val authData =
            try {
                webAuthnManager.validate(authRequest, authParameters)
            } catch (
                @Suppress("TooGenericExceptionCaught") e: WebAuthnException,
            ) {
                log.warn(
                    "passkey.finish: assertion validation failed for credential {}: {}",
                    passkey.id,
                    e.message,
                )
                return Result.InvalidAssertion(e.message ?: "validation failed")
            }

        // Persist new counter + last_used_at, then mint a session.
        val newCounter = authData.authenticatorData?.signCount?.toLong() ?: passkey.signatureCounter
        passkeys.updateAfterUse(passkey.id, newCounter, clock.instant().atOffset(java.time.ZoneOffset.UTC))

        val session = sessions.create(owner.id)
        auditWriter.record(
            AuditEntry(
                action = "user.login.passkey",
                entityType = "user",
                entityId = owner.id,
                actorUserId = owner.id,
                metadata = mapOf("passkeyId" to passkey.id.toString()),
            ),
        )
        log.info(
            "passkey.finish: user={} passkey={} session={}",
            owner.id,
            passkey.id,
            session.id.take(SESSION_LOG_PREFIX),
        )
        return Result.Success(owner, session)
    }

    private fun buildServerProperty(challenge: ByteArray): ServerProperty {
        val origins = properties.origins.map { Origin.create(it) }.toSet()
        return ServerProperty(origins, properties.rpId, DefaultChallenge(challenge), null)
    }

    sealed interface Result {
        data class Success(
            val user: User,
            val session: Session,
        ) : Result

        /** /finish handle did not match anything we issued (timeout / replay / typo). */
        data object MissingChallenge : Result

        /** Browser sent a credential id we don't know. Same shape as InvalidAssertion to the SPA. */
        data object UnknownCredential : Result

        /** The user's account is disabled. */
        data object AccountDisabled : Result

        /** WebAuthn4J rejected the assertion. [reason] is internal-only. */
        data class InvalidAssertion(
            val reason: String,
        ) : Result
    }

    companion object {
        private const val CHALLENGE_BYTES = 32
        private const val SESSION_LOG_PREFIX = 6
        private val ENCODER = Base64.getUrlEncoder().withoutPadding()

        private fun b64url(bytes: ByteArray): String = ENCODER.encodeToString(bytes)
    }
}
