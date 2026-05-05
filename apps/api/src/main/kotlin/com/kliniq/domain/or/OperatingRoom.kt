package com.kliniq.domain.or

import java.time.OffsetDateTime
import java.util.UUID

/**
 * A physical operating room. Identified by [code] (a short, human-friendly
 * label like "OR-1") that's unique per clinic. Lifecycle states:
 *
 *   - ACTIVE:      usable for new bookings.
 *   - MAINTENANCE: hidden from the new-booking picker; existing bookings
 *                  are not migrated (use case decides whether to cancel).
 *   - RETIRED:     archived. Replaces a real DELETE so audit history and
 *                  past bookings keep their FK referent. Retired rooms
 *                  are excluded from default listings.
 */
data class OperatingRoom(
    val id: UUID,
    val code: String,
    val name: String,
    val status: OperatingRoomStatus,
    val notes: String?,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime,
) {
    init {
        require(code.isNotBlank() && code.length <= MAX_CODE) {
            "code must be 1..$MAX_CODE characters"
        }
        require(name.isNotBlank() && name.length <= MAX_NAME) {
            "name must be 1..$MAX_NAME characters"
        }
    }

    val isActive: Boolean get() = status == OperatingRoomStatus.ACTIVE
    val isRetired: Boolean get() = status == OperatingRoomStatus.RETIRED

    companion object {
        const val MAX_CODE = 20
        const val MAX_NAME = 100
    }
}

enum class OperatingRoomStatus { ACTIVE, MAINTENANCE, RETIRED }

/**
 * Fields the application supplies when creating a fresh operating room.
 * `status` defaults to ACTIVE at the DB level; `created_at` / `updated_at`
 * default to `now()`.
 */
data class NewOperatingRoom(
    val id: UUID,
    val code: String,
    val name: String,
    val notes: String? = null,
) {
    init {
        require(code.isNotBlank() && code.length <= OperatingRoom.MAX_CODE) {
            "code must be 1..${OperatingRoom.MAX_CODE} characters"
        }
        require(name.isNotBlank() && name.length <= OperatingRoom.MAX_NAME) {
            "name must be 1..${OperatingRoom.MAX_NAME} characters"
        }
    }
}

/**
 * Partial update. `null` on a field means "leave it as is"; only non-null
 * values are written. Notes can be cleared by sending an empty string —
 * the repository normalises that to NULL.
 */
data class OperatingRoomPatch(
    val name: String? = null,
    val notes: String? = null,
    val status: OperatingRoomStatus? = null,
) {
    init {
        if (name != null) {
            require(name.isNotBlank() && name.length <= OperatingRoom.MAX_NAME) {
                "name must be 1..${OperatingRoom.MAX_NAME} characters"
            }
        }
    }

    val isNoOp: Boolean get() = name == null && notes == null && status == null
}
