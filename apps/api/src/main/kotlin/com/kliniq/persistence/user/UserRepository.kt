package com.kliniq.persistence.user

import com.kliniq.domain.user.NewUser
import com.kliniq.domain.user.User
import java.util.UUID

/**
 * Persistence-layer port for [User]. Implementations must:
 *   - normalize emails to lowercase before lookups (the `users` table has a
 *     generated `email_normalized` column that the DB index uses);
 *   - never expose [com.kliniq.db] generated types beyond this layer.
 *
 * Sprint 0 covers create/find. Update operations (markEmailVerified, etc.)
 * land in Sprint 1 Day 7 alongside the use cases that need them.
 */
interface UserRepository {
    fun create(newUser: NewUser): User

    fun findById(id: UUID): User?

    fun findByEmail(email: String): User?

    fun existsByEmail(email: String): Boolean
}
