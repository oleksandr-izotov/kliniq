package com.kliniq.persistence.user

import com.kliniq.domain.user.NewUser
import com.kliniq.domain.user.Role
import com.kliniq.domain.user.Specialty
import com.kliniq.domain.user.User
import com.kliniq.domain.user.UserStatus
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Persistence-layer port for [User]. Implementations must:
 *   - normalize emails to lowercase before lookups (the `users` table has a
 *     generated `email_normalized` column that the DB index uses);
 *   - never expose [com.kliniq.db] generated types beyond this layer.
 *
 * The function-count suppression below mirrors the impl: a single port
 * over the users table reads cleaner than splitting CRUD across two
 * interfaces just to dodge detekt's threshold.
 */
@Suppress("TooManyFunctions")
interface UserRepository {
    fun create(newUser: NewUser): User

    fun findById(id: UUID): User?

    fun findByEmail(email: String): User?

    fun existsByEmail(email: String): Boolean

    /**
     * Returns active surgeons (is_surgeon = true, status = ACTIVE), ordered by
     * display name. Used by the booking modal's surgeon picker. Disabled
     * surgeons stay hidden so the SPA can't offer a row that the booking
     * use case would reject anyway.
     */
    fun findActiveSurgeons(): List<User>

    /**
     * Stamp `email_verified_at` on the row. Returns true if a row was actually
     * updated (i.e. the user existed and wasn't already verified — we don't
     * overwrite an existing timestamp so audit history stays clean).
     */
    fun markEmailVerified(
        id: UUID,
        verifiedAt: OffsetDateTime,
    ): Boolean

    /**
     * Replace the user's password hash. Returns true if a row was updated.
     * Caller is responsible for invalidating the user's existing sessions
     * separately — see [com.kliniq.infra.security.SessionStore.invalidateAllForUser].
     */
    fun updatePasswordHash(
        id: UUID,
        passwordHash: String,
    ): Boolean

    /**
     * Paginated, filtered listing for the admin user-management UI.
     * Sorted by display_name then email so the page is stable across
     * round-trips. Use [countAdminUsers] alongside for the total.
     */
    fun listAdminUsers(
        filter: UserListFilter,
        page: Int,
        pageSize: Int,
    ): List<User>

    fun countAdminUsers(filter: UserListFilter): Int

    /**
     * Active admins remaining if [excludingId]'s admin powers were
     * removed. Used by the LastAdmin invariant before applying a
     * role-demotion or disable on an admin row.
     */
    fun countActiveAdminsExcluding(excludingId: UUID?): Int

    /**
     * Apply admin-only field changes (role / surgeon flag / specialty /
     * status) atomically. Returns the post-update [User] or null when no
     * row matched [id]. Caller validates the surgeon-iff-specialty
     * invariant; the row constraint at the DB layer is the safety net.
     */
    fun updateAdminFields(
        id: UUID,
        role: Role,
        isSurgeon: Boolean,
        specialty: Specialty?,
        status: UserStatus,
    ): User?
}

/** Optional filters for the admin user listing — all null = no constraint. */
data class UserListFilter(
    /** Case-insensitive substring matched against display_name OR email. */
    val q: String? = null,
    val role: Role? = null,
    val isSurgeon: Boolean? = null,
    val status: UserStatus? = null,
)
