package com.kliniq.domain.invitation

import com.kliniq.domain.user.Role
import com.kliniq.domain.user.Specialty
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Admin-issued one-shot invitation. Carries the role + surgeon flags the
 * admin pre-set; once the recipient accepts, those values are copied
 * straight onto the new user row, so accept can finalize a verified
 * account in one round-trip without a separate promotion endpoint.
 *
 * `tokenHash` is sha-256(plaintext) hex; the plaintext leaves the system
 * exactly once via the invitation email, never logged.
 */
data class Invitation(
    val id: UUID,
    val email: String,
    val role: Role,
    val isSurgeon: Boolean,
    val specialty: Specialty?,
    val tokenHash: String,
    val issuedById: UUID,
    val issuedAt: OffsetDateTime,
    val expiresAt: OffsetDateTime,
    val acceptedAt: OffsetDateTime?,
    val revokedAt: OffsetDateTime?,
) {
    init {
        require(email.isNotBlank()) { "email must not be blank" }
        require((isSurgeon && specialty != null) || (!isSurgeon && specialty == null)) {
            "specialty must be set iff isSurgeon"
        }
        // Belt-and-braces — V4 enforces this at the DB layer too.
        require(!(acceptedAt != null && revokedAt != null)) {
            "an invitation cannot be both accepted and revoked"
        }
    }

    fun isPending(now: OffsetDateTime): Boolean = acceptedAt == null && revokedAt == null && now.isBefore(expiresAt)
}

/**
 * The fields the application provides when creating a new [Invitation].
 * The DB fills in `issued_at` (now) and the four nullable lifecycle
 * timestamps. Same surgeon-iff-specialty invariant as the persisted form.
 */
data class NewInvitation(
    val id: UUID,
    val email: String,
    val role: Role,
    val isSurgeon: Boolean,
    val specialty: Specialty?,
    val tokenHash: String,
    val issuedById: UUID,
    val expiresAt: OffsetDateTime,
) {
    init {
        require(email.isNotBlank()) { "email must not be blank" }
        require((isSurgeon && specialty != null) || (!isSurgeon && specialty == null)) {
            "specialty must be set iff isSurgeon"
        }
    }
}
