package com.kliniq.usecase.invitation

import com.kliniq.domain.invitation.Invitation
import com.kliniq.persistence.invitation.InvitationRepository
import org.springframework.stereotype.Service

@Service
class ListInvitationsUseCase(
    private val invitations: InvitationRepository,
) {
    fun list(includeHistory: Boolean): List<Invitation> = invitations.listAll(includeHistory)
}
