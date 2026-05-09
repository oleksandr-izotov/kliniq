package com.kliniq.usecase.invitation

import com.kliniq.domain.invitation.Invitation
import com.kliniq.persistence.invitation.InvitationRepository
import org.springframework.stereotype.Service
import java.time.Clock

/**
 * Read-only counterpart to [AcceptInvitationUseCase]. The accept page
 * calls this on mount with the token from the URL so it can render
 * "You've been invited as a MANAGER on x@y.local" before the recipient
 * picks a password — and so expired / revoked / already-accepted
 * tokens surface as a friendly message instead of after a wasted form
 * submit.
 *
 * Same classification shape as accept; the only thing that doesn't
 * happen here is the user creation + token consumption.
 */
@Service
class LookupInvitationUseCase(
    private val invitations: InvitationRepository,
    private val tokens: InvitationTokenService,
    private val clock: Clock,
) {
    @Suppress("ReturnCount")
    fun lookup(plaintextToken: String): Result {
        val invitation = invitations.findByTokenHash(tokens.hash(plaintextToken)) ?: return Result.InvalidToken
        val now = clock.instant().atZone(clock.zone).toOffsetDateTime()

        if (invitation.revokedAt != null) return Result.Revoked
        if (invitation.acceptedAt != null) return Result.AlreadyAccepted
        if (!now.isBefore(invitation.expiresAt)) return Result.Expired

        return Result.Pending(invitation)
    }

    sealed interface Result {
        data class Pending(
            val invitation: Invitation,
        ) : Result

        data object InvalidToken : Result

        data object Expired : Result

        data object Revoked : Result

        data object AlreadyAccepted : Result
    }
}
