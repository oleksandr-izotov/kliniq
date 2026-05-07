package com.kliniq.usecase.invitation

import com.kliniq.infra.audit.AuditEntry
import com.kliniq.infra.audit.AuditWriter
import com.kliniq.persistence.invitation.InvitationRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Clock
import java.util.UUID

/**
 * Stamps `revoked_at` on an active invitation, freeing the email so a
 * fresh invite can be issued. The repo's `markRevoked` only matches
 * rows that aren't already terminal — accepted-or-revoked rows return
 * `false` and we surface that as `AlreadyTerminal`.
 */
@Service
class RevokeInvitationUseCase(
    private val invitations: InvitationRepository,
    private val auditWriter: AuditWriter,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Suppress("ReturnCount") // each terminal state surfaces a distinct API code
    fun revoke(
        actorUserId: UUID,
        id: UUID,
    ): Result {
        val before = invitations.findById(id) ?: return Result.NotFound
        val now = clock.instant().atZone(clock.zone).toOffsetDateTime()

        if (before.acceptedAt != null) return Result.AlreadyTerminal
        if (before.revokedAt != null) return Result.AlreadyTerminal

        val ok = invitations.markRevoked(id, now)
        if (!ok) {
            // Lost a race against another revoke / accept.
            return Result.AlreadyTerminal
        }

        auditWriter.record(
            AuditEntry(
                action = "invitation.revoked",
                entityType = "invitation",
                entityId = id,
                actorUserId = actorUserId,
                metadata = mapOf("email" to before.email),
            ),
        )
        log.info("invitation.revoked: id={} email={} by={}", id, before.email, actorUserId)
        return Result.Success
    }

    sealed interface Result {
        data object Success : Result

        data object NotFound : Result

        /** Already accepted or already revoked. */
        data object AlreadyTerminal : Result
    }
}
