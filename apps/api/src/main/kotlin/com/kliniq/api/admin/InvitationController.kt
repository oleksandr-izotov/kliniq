package com.kliniq.api.admin

import com.kliniq.api.error.ApiErrorResponse
import com.kliniq.infra.security.KliniqAuthentication
import com.kliniq.usecase.invitation.CreateInvitationUseCase
import com.kliniq.usecase.invitation.ListInvitationsUseCase
import com.kliniq.usecase.invitation.RevokeInvitationUseCase
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Admin-only invitation endpoints. SecurityConfig gates the admin route
 * group to `hasRole("ADMIN")`, so anything reaching this controller is
 * already an authenticated admin.
 */
@RestController
@RequestMapping("/api/v1/admin/invitations")
class InvitationController(
    private val createUseCase: CreateInvitationUseCase,
    private val listUseCase: ListInvitationsUseCase,
    private val revokeUseCase: RevokeInvitationUseCase,
) {
    @GetMapping
    fun list(
        @RequestParam(name = "includeHistory", defaultValue = "false") includeHistory: Boolean,
    ): List<InvitationDto> = listUseCase.list(includeHistory).map(InvitationDto::of)

    @PostMapping
    fun create(
        @Valid @RequestBody request: CreateInvitationRequest,
    ): ResponseEntity<*> {
        val cmd =
            CreateInvitationUseCase.Command(
                email = request.email,
                role = request.role,
                isSurgeon = request.isSurgeon,
                specialty = request.specialty,
                issuedById = currentUserId(),
            )
        return when (val result = createUseCase.create(cmd)) {
            is CreateInvitationUseCase.Result.Success ->
                ResponseEntity.status(HttpStatus.CREATED).body(InvitationDto.of(result.invitation))
            is CreateInvitationUseCase.Result.InvalidInput ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                    ApiErrorResponse(code = "VALIDATION_ERROR", message = result.reason),
                )
            CreateInvitationUseCase.Result.PendingExists ->
                ResponseEntity.status(HttpStatus.CONFLICT).body(
                    ApiErrorResponse(
                        code = "INVITATION_PENDING",
                        message = "An invitation is already pending for that email.",
                    ),
                )
            CreateInvitationUseCase.Result.UserAlreadyExists ->
                ResponseEntity.status(HttpStatus.CONFLICT).body(
                    ApiErrorResponse(
                        code = "USER_ALREADY_EXISTS",
                        message = "A user with that email already exists.",
                    ),
                )
        }
    }

    @DeleteMapping("/{id}")
    fun revoke(
        @PathVariable id: UUID,
    ): ResponseEntity<*> =
        when (val result = revokeUseCase.revoke(actorUserId = currentUserId(), id = id)) {
            RevokeInvitationUseCase.Result.Success ->
                ResponseEntity.noContent().build<Void>()
            RevokeInvitationUseCase.Result.NotFound ->
                ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                    ApiErrorResponse(code = "NOT_FOUND", message = "Invitation not found."),
                )
            RevokeInvitationUseCase.Result.AlreadyTerminal ->
                ResponseEntity.status(HttpStatus.CONFLICT).body(
                    ApiErrorResponse(
                        code = "ALREADY_TERMINAL",
                        message = "Invitation is already accepted or revoked.",
                    ),
                )
        }

    private fun currentUserId(): UUID =
        (SecurityContextHolder.getContext().authentication as? KliniqAuthentication)
            ?.user
            ?.id
            ?: error("Authenticated endpoint reached without KliniqAuthentication")
}
