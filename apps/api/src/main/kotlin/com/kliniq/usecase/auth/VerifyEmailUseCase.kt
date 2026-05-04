package com.kliniq.usecase.auth

import com.kliniq.persistence.user.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/**
 * Consumes an email-verification token and stamps the matching user's
 * `email_verified_at` column. The token-consumption and user-update happen in
 * a single DB transaction so a partial result can never leave a verified user
 * with a still-active token (or vice versa).
 */
@Service
class VerifyEmailUseCase(
    private val tokens: EmailVerificationTokenService,
    private val users: UserRepository,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun verify(plaintextToken: String): Result {
        val userId = tokens.consume(plaintextToken) ?: return Result.InvalidToken
        val now = clock.instant().atZone(clock.zone).toOffsetDateTime()
        val updated = users.markEmailVerified(userId, now)
        if (!updated) {
            // Idempotent: token was valid but the user is already verified.
            // Treat as success — re-clicking the link should never look broken.
            log.info("verify: user {} was already verified; treating as success", userId)
        }
        return Result.Verified
    }

    sealed interface Result {
        data object Verified : Result

        data object InvalidToken : Result
    }
}
