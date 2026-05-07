package com.kliniq.api.admin

import com.kliniq.api.error.ApiErrorResponse
import com.kliniq.infra.security.KliniqAuthentication
import com.kliniq.persistence.user.UserListFilter
import com.kliniq.usecase.admin.ListUsersUseCase
import com.kliniq.usecase.admin.UpdateUserUseCase
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Admin user-management endpoints. SecurityConfig gates the admin route
 * group to `hasRole("ADMIN")`, so anything reaching this controller is
 * already authenticated as an admin.
 */
@RestController
@RequestMapping("/api/v1/admin/users")
class UserAdminController(
    private val listUsers: ListUsersUseCase,
    private val updateUser: UpdateUserUseCase,
) {
    @GetMapping
    fun list(
        @Valid @ModelAttribute query: AdminUserListQuery,
    ): AdminUserPageDto {
        val filter =
            UserListFilter(
                q = query.q?.takeIf { it.isNotBlank() },
                role = query.role,
                isSurgeon = query.isSurgeon,
                status = query.status,
            )
        val page = listUsers.list(filter, query.page, query.pageSize)
        return AdminUserPageDto(
            items = page.items.map(AdminUserDto::of),
            page = page.page,
            pageSize = page.pageSize,
            total = page.total,
        )
    }

    @PatchMapping("/{id}")
    @Suppress("CyclomaticComplexMethod") // one branch per Result variant
    fun patch(
        @PathVariable id: UUID,
        @RequestBody request: UpdateUserAdminRequest,
    ): ResponseEntity<*> {
        val patch =
            UpdateUserUseCase.Patch(
                role = request.role,
                isSurgeon = request.isSurgeon,
                specialty = request.specialty,
                status = request.status,
            )
        return when (val result = updateUser.update(currentUserId(), id, patch)) {
            is UpdateUserUseCase.Result.Success ->
                ResponseEntity.ok(AdminUserDto.of(result.user))
            UpdateUserUseCase.Result.NotFound ->
                ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                    ApiErrorResponse(code = "NOT_FOUND", message = "User not found."),
                )
            UpdateUserUseCase.Result.InvalidSpecialty ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                    ApiErrorResponse(
                        code = "INVALID_SPECIALTY",
                        message =
                            "isSurgeon=true requires a specialty; isSurgeon=false clears it. " +
                                "Send both fields together.",
                    ),
                )
            UpdateUserUseCase.Result.SelfLockout ->
                ResponseEntity.status(HttpStatus.CONFLICT).body(
                    ApiErrorResponse(
                        code = "SELF_LOCKOUT",
                        message =
                            "Admins can't demote or disable themselves. " +
                                "Ask another admin to make this change.",
                    ),
                )
            UpdateUserUseCase.Result.LastAdmin ->
                ResponseEntity.status(HttpStatus.CONFLICT).body(
                    ApiErrorResponse(
                        code = "LAST_ADMIN",
                        message =
                            "This change would leave the system with no active admins. " +
                                "Promote another user first.",
                    ),
                )
        }
    }

    private fun currentUserId(): UUID =
        (SecurityContextHolder.getContext().authentication as? KliniqAuthentication)
            ?.user
            ?.id
            ?: error("Authenticated endpoint reached without KliniqAuthentication")
}
