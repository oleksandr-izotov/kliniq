package com.kliniq.usecase.invitation

import com.github.f4b6a3.uuid.UuidCreator
import com.kliniq.domain.invitation.Invitation
import com.kliniq.domain.invitation.NewInvitation
import com.kliniq.domain.user.Role
import com.kliniq.domain.user.Specialty
import com.kliniq.infra.audit.AuditEntry
import com.kliniq.infra.audit.AuditWriter
import com.kliniq.infra.mail.EmailSender
import com.kliniq.persistence.invitation.InvitationRepository
import com.kliniq.persistence.user.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Duration
import java.util.UUID

/**
 * Admin-only flow: validate inputs, ensure no pending invite is already
 * out for this email, persist the invitation row with a one-shot token,
 * and email the accept link to the recipient.
 *
 * The `(email_normalized) WHERE pending` partial unique index in V4 is
 * the durable guard against duplicate-pending — the pre-check here
 * exists to surface the friendly error code before we hit the DB.
 *
 * Email goes out AFTER the DB row is written so a network blip can't
 * leave us with a sent email and no row, but a Mailpit/Resend hiccup
 * after commit can leave us with a row and no email — admin can revoke
 * and re-issue. Acceptable trade.
 */
@Service
@Suppress("LongParameterList") // orchestration use case wires many collaborators
class CreateInvitationUseCase(
    private val invitations: InvitationRepository,
    private val users: UserRepository,
    private val tokens: InvitationTokenService,
    private val emailSender: EmailSender,
    private val auditWriter: AuditWriter,
    private val clock: Clock,
    @Value("\${app.web.base-url}") private val webBaseUrl: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    data class Command(
        val email: String,
        val role: Role,
        val isSurgeon: Boolean,
        val specialty: Specialty?,
        val issuedById: UUID,
    )

    @Suppress("ReturnCount", "LongMethod") // ceremony of validation guards
    fun create(cmd: Command): Result {
        val email = cmd.email.trim()
        if (cmd.isSurgeon && cmd.specialty == null) {
            return Result.InvalidInput("specialty required when isSurgeon=true")
        }
        if (!cmd.isSurgeon && cmd.specialty != null) {
            return Result.InvalidInput("specialty must be null when isSurgeon=false")
        }

        // Don't issue invites for emails already attached to a user — the
        // admin should be using the user-management UI to change role on
        // an existing account, not creating a duplicate.
        if (users.existsByEmail(email)) return Result.UserAlreadyExists

        // Pre-check the partial unique index. Race losers below get the
        // same code via the DataIntegrityViolationException handler.
        if (invitations.findPendingByEmail(email) != null) return Result.PendingExists

        val plaintext = tokens.issue()
        val tokenHash = tokens.hash(plaintext)
        val expiresAt =
            clock
                .instant()
                .plus(VALIDITY)
                .atZone(clock.zone)
                .toOffsetDateTime()

        val saved =
            try {
                invitations.create(
                    NewInvitation(
                        id = UuidCreator.getTimeOrderedEpoch(),
                        email = email,
                        role = cmd.role,
                        isSurgeon = cmd.isSurgeon,
                        specialty = cmd.specialty,
                        tokenHash = tokenHash,
                        issuedById = cmd.issuedById,
                        expiresAt = expiresAt,
                    ),
                )
            } catch (_: DataIntegrityViolationException) {
                // Pending-uniqueness race lost.
                return Result.PendingExists
            }

        val acceptUrl = "$webBaseUrl/invite?token=$plaintext"
        emailSender.sendInvitation(
            to = saved.email,
            roleLabel =
                saved.role.name
                    .lowercase()
                    .replaceFirstChar { it.uppercase() },
            acceptUrl = acceptUrl,
        )

        auditWriter.record(
            AuditEntry(
                action = "invitation.created",
                entityType = "invitation",
                entityId = saved.id,
                actorUserId = cmd.issuedById,
                after =
                    mapOf(
                        "email" to saved.email,
                        "role" to saved.role.name,
                        "isSurgeon" to saved.isSurgeon,
                        "specialty" to saved.specialty?.name,
                        "expiresAt" to saved.expiresAt.toString(),
                    ),
            ),
        )
        log.info(
            "invitation.created: id={} email={} role={} by={}",
            saved.id,
            saved.email,
            saved.role,
            cmd.issuedById,
        )
        return Result.Success(saved)
    }

    sealed interface Result {
        data class Success(
            val invitation: Invitation,
        ) : Result

        data class InvalidInput(
            val reason: String,
        ) : Result

        data object PendingExists : Result

        data object UserAlreadyExists : Result
    }

    companion object {
        private val VALIDITY: Duration = Duration.ofDays(7)
    }
}
