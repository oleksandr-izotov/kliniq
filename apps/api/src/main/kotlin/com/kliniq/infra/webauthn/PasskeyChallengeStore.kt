package com.kliniq.infra.webauthn

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

/**
 * Tracks in-flight WebAuthn challenges in Redis. Two flavours:
 *
 *   - Registration challenges are keyed by the authenticated user id, since
 *     that's already known when the ceremony starts. Only one in-flight
 *     registration per user is allowed; a second begin() overwrites the
 *     first, which is correct UX (the first browser prompt was abandoned).
 *
 *   - Authentication challenges are keyed by an opaque random handle that
 *     we hand back to the client. The browser echoes it on the finish call.
 *     This avoids embedding the (still anonymous) user identity in a cookie
 *     and lets the same flow serve username-first and discoverable-credential
 *     ("passwordless") authentication.
 *
 * Every entry expires per [WebAuthnProperties.challengeTtl] so abandoned
 * ceremonies don't pile up. Successful consume() calls also delete the
 * entry to make replays impossible.
 */
@Component
class PasskeyChallengeStore(
    private val redis: StringRedisTemplate,
    private val mapper: ObjectMapper,
    private val properties: WebAuthnProperties,
) {
    private val rng = SecureRandom()

    fun storeRegistration(
        userId: UUID,
        challenge: ByteArray,
    ) {
        redis.opsForValue().set(regKey(userId), ENCODER.encodeToString(challenge), properties.challengeTtl)
    }

    fun consumeRegistration(userId: UUID): ByteArray? {
        val key = regKey(userId)
        val raw = redis.opsForValue().get(key) ?: return null
        redis.delete(key)
        return DECODER.decode(raw)
    }

    /**
     * Generate a fresh handle, stash the [challenge] (and optional [userId]
     * when the SPA pre-supplied an email), return the handle to the caller.
     */
    fun storeAuthentication(
        challenge: ByteArray,
        userId: UUID?,
    ): String {
        val handle = newHandle()
        val payload = AuthEntry(challenge = ENCODER.encodeToString(challenge), userId = userId?.toString())
        redis.opsForValue().set(authKey(handle), mapper.writeValueAsString(payload), properties.challengeTtl)
        return handle
    }

    fun consumeAuthentication(handle: String): AuthChallenge? {
        val key = authKey(handle)
        val raw = redis.opsForValue().get(key) ?: return null
        redis.delete(key)
        val entry = mapper.readValue(raw, AuthEntry::class.java)
        return AuthChallenge(
            challenge = DECODER.decode(entry.challenge),
            userId = entry.userId?.let(UUID::fromString),
        )
    }

    private fun newHandle(): String {
        val raw = ByteArray(HANDLE_BYTES).also(rng::nextBytes)
        return ENCODER.encodeToString(raw)
    }

    private fun regKey(userId: UUID) = "$REG_PREFIX$userId"

    private fun authKey(handle: String) = "$AUTH_PREFIX$handle"

    /** Wire shape for Redis. Public so Jackson can read it back. */
    data class AuthEntry
        @JsonCreator
        constructor(
            @JsonProperty("challenge") val challenge: String,
            @JsonProperty("userId") val userId: String?,
        )

    /** Decoded challenge handed to use cases. */
    data class AuthChallenge(
        val challenge: ByteArray,
        val userId: UUID?,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is AuthChallenge) return false
            return challenge.contentEquals(other.challenge) && userId == other.userId
        }

        override fun hashCode(): Int = 31 * challenge.contentHashCode() + (userId?.hashCode() ?: 0)
    }

    companion object {
        const val REG_PREFIX = "kliniq:passkey-reg:"
        const val AUTH_PREFIX = "kliniq:passkey-auth:"
        private const val HANDLE_BYTES = 32
        private val ENCODER = Base64.getUrlEncoder().withoutPadding()
        private val DECODER = Base64.getUrlDecoder()
    }
}
