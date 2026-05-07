package com.kliniq.api.admin

import com.kliniq.domain.invitation.Invitation
import com.kliniq.domain.user.Role
import com.kliniq.domain.user.Specialty
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Wire shape for an invitation. Token plaintext is intentionally absent —
 * it leaves the system exactly once, in the email body.
 */
data class InvitationDto(
    val id: UUID,
    val email: String,
    val role: Role,
    val isSurgeon: Boolean,
    val specialty: Specialty?,
    val issuedById: UUID,
    val issuedAt: OffsetDateTime,
    val expiresAt: OffsetDateTime,
    val acceptedAt: OffsetDateTime?,
    val revokedAt: OffsetDateTime?,
) {
    companion object {
        fun of(invite: Invitation): InvitationDto =
            InvitationDto(
                id = invite.id,
                email = invite.email,
                role = invite.role,
                isSurgeon = invite.isSurgeon,
                specialty = invite.specialty,
                issuedById = invite.issuedById,
                issuedAt = invite.issuedAt,
                expiresAt = invite.expiresAt,
                acceptedAt = invite.acceptedAt,
                revokedAt = invite.revokedAt,
            )
    }
}

data class CreateInvitationRequest(
    @field:NotBlank
    @field:Email
    @field:Size(min = 3, max = MAX_EMAIL)
    val email: String,
    @field:NotNull val role: Role,
    val isSurgeon: Boolean = false,
    val specialty: Specialty? = null,
) {
    companion object {
        const val MAX_EMAIL = 254
    }
}
