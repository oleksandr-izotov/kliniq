package com.kliniq.usecase.auth

import com.kliniq.infra.audit.AuditEntry
import com.kliniq.infra.audit.AuditWriter
import com.kliniq.infra.security.PasswordHasher
import com.kliniq.infra.security.SessionStore
import com.kliniq.persistence.user.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Consumes a password-reset token and replaces the stored hash. After the
 * commit, every existing session for that user is killed — a successful
 * reset implies the prior password may be compromised, so we boot the user
 * off all devices and force a fresh login.
 *
 * Per ASVS V2.7.2, tokens are single-use: [PasswordResetTokenService.consume]
 * marks the consumed timestamp atomically. Any other pending tokens for the
 * user are also cleared so an attacker who somehow grabbed a second link
 * can't reuse it.
 */
@Service
class ResetPasswordUseCase(
    private val tokens: PasswordResetTokenService,
    private val users: UserRepository,
    private val passwordHasher: PasswordHasher,
    private val sessions: SessionStore,
    private val auditWriter: AuditWriter,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    @Suppress("ReturnCount") // each branch represents a distinct failure mode
    fun reset(
        plaintextToken: String,
        newPassword: String,
    ): Result {
        val userId = tokens.consume(plaintextToken) ?: return Result.InvalidToken
        val newHash = passwordHasher.hash(newPassword)
        val updated = users.updatePasswordHash(userId, newHash)
        if (!updated) {
            // The user behind a valid-looking token is gone — treat as invalid.
            log.warn("reset-password: token referenced missing user {}", userId)
            return Result.InvalidToken
        }
        tokens.invalidatePendingFor(userId)
        auditWriter.record(
            AuditEntry(
                action = "user.password_reset_completed",
                entityType = "user",
                entityId = userId,
                actorUserId = userId,
            ),
        )
        // Sessions live in Redis, not the DB, so this isn't part of the TX.
        // Worst case: TX commits and Redis call fails — user logged in on
        // other devices for at most one TTL window (30 days). Acceptable.
        val killed = sessions.invalidateAllForUser(userId)
        log.info("reset-password: user {} updated; invalidated {} sessions", userId, killed)
        return Result.Success
    }

    sealed interface Result {
        data object Success : Result

        data object InvalidToken : Result
    }
}
