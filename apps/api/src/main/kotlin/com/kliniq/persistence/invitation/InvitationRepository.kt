package com.kliniq.persistence.invitation

import com.kliniq.domain.invitation.Invitation
import com.kliniq.domain.invitation.NewInvitation
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Persistence port for [Invitation]. Implementations must:
 *   - lower-case the email at the boundary so the partial unique index
 *     on `email_normalized` actually does its job;
 *   - never expose `com.kliniq.db` jOOQ types beyond this layer.
 */
interface InvitationRepository {
    fun create(invite: NewInvitation): Invitation

    fun findById(id: UUID): Invitation?

    /**
     * Look up by token hash. Used by the public accept flow; intentionally
     * does NOT filter on accepted/revoked/expired so the use case can
     * surface the right code (410 vs 409) instead of a generic 404.
     */
    fun findByTokenHash(tokenHash: String): Invitation?

    /**
     * Pending invitation for [email] (case-insensitive). Returns null if
     * none exists or all rows for that email are accepted/revoked.
     * Used by CreateInvitation to fail fast with INVITATION_PENDING.
     */
    fun findPendingByEmail(email: String): Invitation?

    fun listAll(includeHistory: Boolean): List<Invitation>

    /** Stamps `accepted_at`. Returns true if a row was actually updated. */
    fun markAccepted(
        id: UUID,
        acceptedAt: OffsetDateTime,
    ): Boolean

    /** Stamps `revoked_at`. Returns true if a row was actually updated. */
    fun markRevoked(
        id: UUID,
        revokedAt: OffsetDateTime,
    ): Boolean
}
