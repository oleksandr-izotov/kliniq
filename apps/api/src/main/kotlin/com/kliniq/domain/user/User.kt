package com.kliniq.domain.user

import java.time.OffsetDateTime
import java.util.UUID

/**
 * A login identity. Mirrors the `users` table 1:1, with two derived booleans
 * the use cases find handy. Construction enforces invariants the DB also
 * enforces (specialty iff surgeon, display name length) so unit tests don't
 * need to spin up Postgres to assert them.
 */
data class User(
    val id: UUID,
    val email: String,
    val emailVerifiedAt: OffsetDateTime?,
    val passwordHash: String?,
    val displayName: String,
    val role: Role,
    val isSurgeon: Boolean,
    val specialty: Specialty?,
    val status: UserStatus,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime,
) {
    init {
        require(email.isNotBlank()) { "email must not be blank" }
        require(displayName.isNotBlank() && displayName.length <= MAX_DISPLAY_NAME) {
            "displayName must be between 1 and $MAX_DISPLAY_NAME characters"
        }
        require((isSurgeon && specialty != null) || (!isSurgeon && specialty == null)) {
            "specialty must be set iff isSurgeon is true"
        }
    }

    val isEmailVerified: Boolean get() = emailVerifiedAt != null
    val isActive: Boolean get() = status == UserStatus.ACTIVE
    val canLogin: Boolean get() = isEmailVerified && isActive

    companion object {
        const val MAX_DISPLAY_NAME = 100
    }
}

enum class Role { ADMIN, MANAGER, STAFF }

enum class UserStatus { ACTIVE, DISABLED }

enum class Specialty {
    CARDIOLOGY,
    ORTHOPEDICS,
    GENERAL,
    NEUROSURGERY,
    OPHTHALMOLOGY,
}

/**
 * The fields the application provides when creating a new [User]. The DB
 * fills in `email_verified_at` (null), `status` (ACTIVE), `created_at`,
 * and `updated_at`. The same surgeon/specialty invariant as on [User]
 * applies — this type validates it before any SQL runs.
 */
data class NewUser(
    val id: UUID,
    val email: String,
    val passwordHash: String?,
    val displayName: String,
    val role: Role,
    val isSurgeon: Boolean,
    val specialty: Specialty?,
) {
    init {
        require(email.isNotBlank()) { "email must not be blank" }
        require(displayName.isNotBlank() && displayName.length <= User.MAX_DISPLAY_NAME) {
            "displayName must be between 1 and ${User.MAX_DISPLAY_NAME} characters"
        }
        require((isSurgeon && specialty != null) || (!isSurgeon && specialty == null)) {
            "specialty must be set iff isSurgeon is true"
        }
    }
}
