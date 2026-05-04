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
     * Stamp `email_verified_at` on the row. Returns true if a row was actually
     * updated (i.e. the user existed and wasn't already verified — we don't
     * overwrite an existing timestamp so audit history stays clean).
     */
    fun markEmailVerified(
        id: UUID,
        verifiedAt: OffsetDateTime,
    ): Boolean
}
