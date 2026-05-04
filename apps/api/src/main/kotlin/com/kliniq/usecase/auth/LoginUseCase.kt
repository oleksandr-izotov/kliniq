package com.kliniq.usecase.auth

import com.kliniq.domain.auth.Session
import com.kliniq.domain.user.User
import com.kliniq.infra.audit.AuditEntry
import com.kliniq.infra.audit.AuditWriter
import com.kliniq.infra.security.PasswordHasher
import com.kliniq.infra.security.SessionStore
import com.kliniq.persistence.user.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * Verifies email + password and creates a Redis session. Designed to leak as
 * little information as possible:
 *   - Wrong password and unknown email both produce InvalidCredentials.
 *   - Even the unknown-email branch runs an Argon2 verification against a
 *     dummy hash so request timing is roughly constant whether the email
 *     exists or not.
 *
 * Email-not-verified and account-disabled are exposed as distinct results so
 * the UI can show "please verify your email" — those states are recoverable
 * from the user's side and not an attacker-information leak.
 */
@Service
class LoginUseCase(
    private val users: UserRepository,
    private val passwordHasher: PasswordHasher,
    private val sessions: SessionStore,
    private val auditWriter: AuditWriter,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Pre-computed Argon2id hash used for the timing-equalisation path. The
     * actual password is irrelevant — we just need any valid encoded hash for
     * the verifier to spend its time on.
     */
    private val timingDummyHash: String = passwordHasher.hash("kliniq:timing-dummy-do-not-match")

    @Suppress("ReturnCount") // each failure mode is clearer as an early return
    fun login(
        email: String,
        password: String,
    ): Result {
        val user = users.findByEmail(email)
        if (user == null) {
            // Burn cycles so the timing of "unknown email" matches "known email".
            passwordHasher.matches(password, timingDummyHash)
            return Result.InvalidCredentials
        }
        if (!user.isEmailVerified) return Result.EmailNotVerified
        if (!user.isActive) return Result.AccountDisabled

        val storedHash = user.passwordHash
        if (storedHash == null) {
            // Account exists but is passkey-only — wrong factor for this endpoint.
            passwordHasher.matches(password, timingDummyHash)
            return Result.InvalidCredentials
        }
        if (!passwordHasher.matches(password, storedHash)) return Result.InvalidCredentials

        val session = sessions.create(user.id)
        auditWriter.record(
            AuditEntry(
                action = "user.login",
                entityType = "user",
                entityId = user.id,
                actorUserId = user.id,
            ),
        )
        log.info("login: user {} authenticated; session {}", user.id, session.id.take(SESSION_LOG_PREFIX))
        return Result.Success(user, session)
    }

    sealed interface Result {
        data class Success(
            val user: User,
            val session: Session,
        ) : Result

        data object InvalidCredentials : Result

        data object EmailNotVerified : Result

        data object AccountDisabled : Result
    }

    companion object {
        // Log only a short prefix of the session id; full id is a credential.
        private const val SESSION_LOG_PREFIX = 6
    }
}
