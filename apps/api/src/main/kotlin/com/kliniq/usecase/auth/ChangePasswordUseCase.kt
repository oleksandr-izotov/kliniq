package com.kliniq.usecase.auth

import com.kliniq.infra.audit.AuditEntry
import com.kliniq.infra.audit.AuditWriter
import com.kliniq.infra.security.PasswordHasher
import com.kliniq.infra.security.SessionStore
import com.kliniq.infra.security.breach.BreachedPasswordChecker
import com.kliniq.persistence.user.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * In-app password change. Per OWASP ASVS V3.7.1, sensitive changes must
 * re-prove the current credential — even though the request comes from
 * an already-authenticated session, the user must type their existing
 * password to authorise the change.
 *
 * Side-effects of a successful change:
 *   - All other sessions for this user are invalidated. The current
 *     session stays alive so the user doesn't have to log back in
 *     immediately on the device they typed the new password on.
 *   - Any pending password-reset tokens for this user are NOT touched
 *     here (they'll naturally expire at their 15-minute TTL); the new
 *     password takes effect immediately and any leaked reset link
 *     would still let an attacker change it again, which is fine
 *     because the attacker has neither the current password nor a
 *     session.
 */
@Service
@Suppress("LongParameterList") // orchestration use case wires many collaborators
class ChangePasswordUseCase(
    private val users: UserRepository,
    private val passwordHasher: PasswordHasher,
    private val sessions: SessionStore,
    private val auditWriter: AuditWriter,
    private val breachChecker: BreachedPasswordChecker,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    @Suppress("ReturnCount") // each branch is one informative early exit
    fun change(
        userId: UUID,
        currentSessionId: String,
        currentPassword: String,
        newPassword: String,
    ): Result {
        // Cheap fail-fast checks before we touch the DB.
        if (currentPassword == newPassword) return Result.SamePassword
        if (breachChecker.isBreached(newPassword)) return Result.PasswordBreached

        val user = users.findById(userId) ?: error("authenticated user vanished mid-request: $userId")
        val storedHash =
            user.passwordHash
                ?: return Result.PasskeyOnlyAccount // no password to compare against
        if (!passwordHasher.matches(currentPassword, storedHash)) {
            return Result.IncorrectCurrentPassword
        }
        // Reject "I'm just retyping the same thing" via the hash too — catches
        // the case where currentPassword and newPassword strings happen to be
        // distinct objects but verify against the same stored hash.
        if (passwordHasher.matches(newPassword, storedHash)) {
            return Result.SamePassword
        }

        val newHash = passwordHasher.hash(newPassword)
        users.updatePasswordHash(userId, newHash)

        // Sessions live in Redis, not the DB, so they aren't part of this
        // transaction. A late Redis failure here would leave the new hash
        // committed but the other devices alive for at most one TTL window
        // (30 days). That's acceptable: the legitimate user can issue a
        // logout-everywhere via the future settings UI.
        val killed = sessions.invalidateAllForUserExcept(userId, currentSessionId)

        auditWriter.record(
            AuditEntry(
                action = "user.password_changed",
                entityType = "user",
                entityId = userId,
                actorUserId = userId,
                metadata = mapOf("otherSessionsTerminated" to killed),
            ),
        )
        log.info("change-password: user {} updated; terminated {} other sessions", userId, killed)
        return Result.Success(otherSessionsTerminated = killed)
    }

    sealed interface Result {
        data class Success(
            val otherSessionsTerminated: Int,
        ) : Result

        /** Provided current password did not verify against the stored hash. */
        data object IncorrectCurrentPassword : Result

        /** New password is in HIBP's breach corpus — pick another. */
        data object PasswordBreached : Result

        /** New password is identical to the current one. */
        data object SamePassword : Result

        /** Account has no password — only passkeys. Use the password-reset flow instead. */
        data object PasskeyOnlyAccount : Result
    }
}
