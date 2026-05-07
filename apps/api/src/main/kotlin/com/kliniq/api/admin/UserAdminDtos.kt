package com.kliniq.api.admin

import com.kliniq.domain.user.Role
import com.kliniq.domain.user.Specialty
import com.kliniq.domain.user.User
import com.kliniq.domain.user.UserStatus
import jakarta.validation.constraints.Min
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Wire shape for an admin-listed user. Includes the email — admins
 * legitimately need to identify rows — but excludes `passwordHash`
 * and any session-bound state.
 */
data class AdminUserDto(
    val id: UUID,
    val email: String,
    val displayName: String,
    val role: Role,
    val isSurgeon: Boolean,
    val specialty: Specialty?,
    val status: UserStatus,
    val emailVerifiedAt: OffsetDateTime?,
    val createdAt: OffsetDateTime,
) {
    companion object {
        fun of(u: User): AdminUserDto =
            AdminUserDto(
                id = u.id,
                email = u.email,
                displayName = u.displayName,
                role = u.role,
                isSurgeon = u.isSurgeon,
                specialty = u.specialty,
                status = u.status,
                emailVerifiedAt = u.emailVerifiedAt,
                createdAt = u.createdAt,
            )
    }
}

/** Paginated response. `total` lets the SPA render "showing N of M" without a second call. */
data class AdminUserPageDto(
    val items: List<AdminUserDto>,
    val page: Int,
    val pageSize: Int,
    val total: Int,
)

/**
 * Partial update. Each field omitted means "no change". `isSurgeon` and
 * `specialty` are validated together server-side: true requires a
 * specialty, false clears it.
 */
data class UpdateUserAdminRequest(
    val role: Role? = null,
    val isSurgeon: Boolean? = null,
    val specialty: Specialty? = null,
    val status: UserStatus? = null,
)

/**
 * Bean-validated query parameters for the listing endpoint. Pulled into
 * a class so springdoc emits a single typed shape the SPA's codegen
 * can consume.
 */
data class AdminUserListQuery(
    val q: String? = null,
    val role: Role? = null,
    val isSurgeon: Boolean? = null,
    val status: UserStatus? = null,
    @field:Min(0) val page: Int = 0,
    @field:Min(1) val pageSize: Int = 50,
)
