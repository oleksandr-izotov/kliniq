package com.kliniq.persistence.user

import com.kliniq.domain.user.NewUser
import com.kliniq.domain.user.User
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Persistence-layer port for [User]. Implementations must:
 *   - normalize emails to lowercase before lookups (the `users` table has a
 *     generated `email_normalized` column that the DB index uses);
 *   - never expose [com.kliniq.db] generated types beyond this layer.
 */
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
}
