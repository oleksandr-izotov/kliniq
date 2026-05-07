package com.kliniq.usecase.invitation

import org.springframework.stereotype.Service
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Issues plaintext invitation tokens and computes their stored hash.
 *
 * Token format: 32 cryptographically random bytes, encoded as URL-safe
 * base64 without padding (43 chars). 256 bits of entropy at issue time
 * means a salt-less SHA-256 of the bytes is fine on the storage side —
 * the search space is too large for any precomputation attack.
 *
 * The plaintext leaves the system exactly once via the invitation email
 * and is never logged. All persistence and lookup paths use [hash].
 */
@Service
class InvitationTokenService {
    private val rng = SecureRandom()

    /** Returns the plaintext token. Caller must email it and never log it. */
    fun issue(): String {
        val raw = ByteArray(TOKEN_BYTES).also(rng::nextBytes)
        return base64UrlEncoder.encodeToString(raw)
    }

    /** Hex-encoded sha-256 digest of the plaintext, as stored in user_invitations.token_hash. */
    fun hash(plaintext: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(plaintext.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val TOKEN_BYTES = 32
        private val base64UrlEncoder = Base64.getUrlEncoder().withoutPadding()
    }
}
