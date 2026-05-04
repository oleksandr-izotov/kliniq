package com.kliniq.usecase.auth

import com.kliniq.infra.audit.AuditEntry
import com.kliniq.infra.audit.AuditWriter
import com.kliniq.infra.mail.EmailSender
import com.kliniq.persistence.user.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate

/**
 * Issues a password-reset link. Returns to the caller without revealing
 * whether the email belongs to a registered user — same neutral-response
 * trick as [RegisterUseCase].
 *
 * Only verified, active accounts get an email. Unverified or disabled
 * accounts get the same neutral response so an attacker can't probe state.
 */
@Service
class ForgotPasswordUseCase(
    private val users: UserRepository,
    private val tokens: PasswordResetTokenService,
    private val emailSender: EmailSender,
    private val auditWriter: AuditWriter,
    private val tx: TransactionTemplate,
    @Value("\${app.web.base-url}") private val webBaseUrl: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun forgot(email: String) {
        val pending = tx.execute { issueIfEligible(email) }
        pending?.let { dispatchEmail(it) }
    }

    @Suppress("ReturnCount") // each early return reflects a distinct neutral-response branch
    private fun issueIfEligible(email: String): PendingReset? {
        val user = users.findByEmail(email) ?: return null
        if (!user.canLogin) {
            log.info(
                "forgot-password: user {} not eligible (verified={}, active={})",
                user.id,
                user.isEmailVerified,
                user.isActive,
            )
            return null
        }
        val plaintext = tokens.issue(user.id)
        auditWriter.record(
            AuditEntry(
                action = "user.password_reset_requested",
                entityType = "user",
                entityId = user.id,
                actorUserId = user.id,
            ),
        )
        return PendingReset(user.email, user.displayName, plaintext)
    }

    private fun dispatchEmail(pending: PendingReset) {
        val resetUrl = "$webBaseUrl/reset?token=${pending.token}"
        emailSender.sendPasswordReset(pending.to, pending.displayName, resetUrl)
    }

    private data class PendingReset(
        val to: String,
        val displayName: String,
        val token: String,
    )
}
