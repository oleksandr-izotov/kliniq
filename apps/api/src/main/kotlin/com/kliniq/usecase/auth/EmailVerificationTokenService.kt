package com.kliniq.usecase.auth

import com.kliniq.persistence.auth.EmailVerificationTokenRepository
import org.springframework.stereotype.Service
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.util.Base64
import java.util.UUID

/**
 * Issues and consumes single-use, time-limited tokens used for email
 * verification at registration. The plaintext token is sent in the
 * verification email; only its SHA-256 digest is ever stored.
 *
 * Token format: 32 cryptographically random bytes, encoded as URL-safe base64
 * without padding (43 chars). That's well above OWASP ASVS V2.7.1's 20-bit
 * minimum and short enough to fit cleanly in a URL.
 */
@Service
class EmailVerificationTokenService(
    private val tokens: EmailVerificationTokenRepository,
    private val clock: Clock,
) {
    private val rng = SecureRandom()

    /**
     * Generate a new token, persist its hash, and return the plaintext form
     * (caller must email it to the user immediately and never log it).
     */
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

    /**
     * Atomically validate and consume the token. Returns the user id on
     * success, null when the token is unknown, expired, or already consumed.
     */
    fun consume(plaintext: String): UUID? {
        val raw = decodeBase64UrlOrNull(plaintext) ?: return null
        val hash = sha256(raw)
        val now = clock.instant().atZone(clock.zone).toOffsetDateTime()
        val userId = tokens.findActiveUserIdByHash(hash, now)
        return userId?.takeIf { tokens.markConsumed(hash, now) }
    }

    private fun decodeBase64UrlOrNull(plaintext: String): ByteArray? =
        try {
            base64UrlDecoder.decode(plaintext)
        } catch (_: IllegalArgumentException) {
            null
        }

    private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    companion object {
        private const val TOKEN_BYTES = 32
        private val VALIDITY: Duration = Duration.ofHours(24)
        private val base64UrlEncoder = Base64.getUrlEncoder().withoutPadding()
        private val base64UrlDecoder = Base64.getUrlDecoder()
    }
}
