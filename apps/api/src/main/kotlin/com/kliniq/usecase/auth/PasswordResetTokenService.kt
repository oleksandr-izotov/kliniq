package com.kliniq.usecase.auth

import com.kliniq.persistence.auth.PasswordResetTokenRepository
import org.springframework.stereotype.Service
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.util.Base64
import java.util.UUID

/**
 * Same shape as [EmailVerificationTokenService] but with a 15-minute window
 * (per OWASP ASVS V2.7.3). 32 random bytes, only SHA-256 stored in the DB.
 */
@Service
class PasswordResetTokenService(
    private val tokens: PasswordResetTokenRepository,
    private val clock: Clock,
) {
    private val rng = SecureRandom()

    fun issue(userId: UUID): String {
        val raw = ByteArray(TOKEN_BYTES).also(rng::nextBytes)
        val plaintext = base64UrlEncoder.encodeToString(raw)
        val hash = sha256(raw)
        val expiresAt =
            clock
                .instant()
                .plus(VALIDITY)
                .atZone(clock.zone)
                .toOffsetDateTime()
        tokens.insert(hash, userId, expiresAt)
        return plaintext
    }

    fun consume(plaintext: String): UUID? {
        val raw = decodeBase64UrlOrNull(plaintext) ?: return null
        val hash = sha256(raw)
        val now = clock.instant().atZone(clock.zone).toOffsetDateTime()
        val userId = tokens.findActiveUserIdByHash(hash, now)
        return userId?.takeIf { tokens.markConsumed(hash, now) }
    }

    /** Drops any other pending tokens for this user — call after a successful reset. */
    fun invalidatePendingFor(userId: UUID) = tokens.deleteByUserId(userId)

    private fun decodeBase64UrlOrNull(plaintext: String): ByteArray? =
        try {
            base64UrlDecoder.decode(plaintext)
        } catch (_: IllegalArgumentException) {
            null
        }

    private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    companion object {
        private const val TOKEN_BYTES = 32
        private val VALIDITY: Duration = Duration.ofMinutes(15)
        private val base64UrlEncoder = Base64.getUrlEncoder().withoutPadding()
        private val base64UrlDecoder = Base64.getUrlDecoder()
    }
}
