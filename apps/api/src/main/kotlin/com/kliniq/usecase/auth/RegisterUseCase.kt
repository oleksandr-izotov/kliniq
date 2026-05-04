package com.kliniq.usecase.auth

import com.github.f4b6a3.uuid.UuidCreator
import com.kliniq.domain.user.NewUser
import com.kliniq.domain.user.Role
import com.kliniq.infra.mail.EmailSender
import com.kliniq.infra.security.PasswordHasher
import com.kliniq.persistence.user.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID

/**
 * Registers a new staff user.
 *
 * Behaviour by design (per ASVS V2.5.4 + SPRINT_1.md):
 *   - Always returns success without revealing whether the email is already
 *     in use. The actual user creation only happens for fresh emails.
 *   - The verification email is sent **after** the DB transaction commits, so
 *     a partial commit + email-out-of-band can never happen.
 */
@Service
class RegisterUseCase(
    private val users: UserRepository,
    private val tokens: EmailVerificationTokenService,
    private val passwordHasher: PasswordHasher,
    private val emailSender: EmailSender,
    private val tx: TransactionTemplate,
    @Value("\${app.web.base-url}") private val webBaseUrl: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    data class RegisterCommand(
        val email: String,
        val password: String,
        val displayName: String,
    )

    fun register(cmd: RegisterCommand) {
        val pending = tx.execute { createUserIfNew(cmd) }
        pending?.let { dispatchVerification(it) }
    }

    private fun createUserIfNew(cmd: RegisterCommand): PendingVerification? {
        if (users.existsByEmail(cmd.email)) {
            log.info("register: email already in use; responding with neutral success")
            return null
        }
        val userId: UUID = UuidCreator.getTimeOrderedEpoch()
        users.create(
            NewUser(
                id = userId,
                email = cmd.email,
                passwordHash = passwordHasher.hash(cmd.password),
                displayName = cmd.displayName,
                role = Role.STAFF,
                isSurgeon = false,
                specialty = null,
            ),
        )
        val plaintext = tokens.issue(userId)
        log.info("register: created user {} and issued verification token", userId)
        return PendingVerification(cmd.email, cmd.displayName, plaintext)
    }

    private fun dispatchVerification(pending: PendingVerification) {
        val verifyUrl = "$webBaseUrl/verify?token=${pending.token}"
        emailSender.sendEmailVerification(pending.to, pending.displayName, verifyUrl)
    }

    private data class PendingVerification(
        val to: String,
        val displayName: String,
        val token: String,
    )
}
