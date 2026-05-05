package com.kliniq.domain.booking

import java.time.OffsetDateTime
import java.util.UUID

/**
 * A scheduled use of an operating room by a surgeon, identified by an
 * anonymous patient reference. Mirrors the `bookings` table 1:1.
 *
 * The half-open `[startsAt, endsAt)` interval is the contract — two
 * bookings whose intervals share only the endpoint (a 9–10 followed by
 * a 10–11) are NOT considered overlapping. The DB-level EXCLUDE USING
 * gist constraint and the application-layer overlap query both use
 * this same convention so they agree.
 *
 * `status` drives the booking-overlap predicate: only SCHEDULED and
 * IN_PROGRESS rows block the slot. Cancelling or completing a booking
 * frees its time without deleting the audit history.
 */
data class Booking(
    val id: UUID,
    val operatingRoomId: UUID,
    val surgeonId: UUID,
    val createdById: UUID,
    val startsAt: OffsetDateTime,
    val endsAt: OffsetDateTime,
    val opType: String,
    val patientRef: String,
    val status: BookingStatus,
    val notes: String?,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime,
) {
    init {
        require(opType.isNotBlank() && opType.length <= MAX_OP_TYPE) {
            "opType must be 1..$MAX_OP_TYPE characters"
        }
        require(PATIENT_REF_REGEX.matches(patientRef)) {
            "patientRef must match $PATIENT_REF_PATTERN"
        }
        require(endsAt.isAfter(startsAt)) {
            "endsAt must be strictly after startsAt"
        }
    }

    val timeRange: BookingTimeRange get() = BookingTimeRange(startsAt, endsAt)

    /** Currently blocking the OR slot — the EXCLUDE predicate's domain. */
    val isActive: Boolean get() = status == BookingStatus.SCHEDULED || status == BookingStatus.IN_PROGRESS

    companion object {
        const val MAX_OP_TYPE = 200
        const val MAX_NOTES = 2_000
        const val PATIENT_REF_PATTERN = "^P-[0-9]{4}-[0-9]{3,}\$"
        val PATIENT_REF_REGEX = Regex(PATIENT_REF_PATTERN)
    }
}

/**
 * Lifecycle:
 *
 *     SCHEDULED ──► IN_PROGRESS ──► COMPLETED
 *         │             │
 *         └─────────────┴────────► CANCELLED
 *
 * COMPLETED and CANCELLED are terminal — once entered, no further
 * transitions are allowed. SCHEDULED → COMPLETED skipping IN_PROGRESS
 * is forbidden so audit timeline reflects what actually happened.
 */
enum class BookingStatus {
    SCHEDULED,
    IN_PROGRESS,
    COMPLETED,
    CANCELLED,
    ;

    /** True when the predicate `status IN (SCHEDULED, IN_PROGRESS)` should match. */
    val blocksSlot: Boolean get() = this == SCHEDULED || this == IN_PROGRESS

    fun canTransitionTo(target: BookingStatus): Boolean =
        when (this) {
            SCHEDULED -> target == IN_PROGRESS || target == CANCELLED
            IN_PROGRESS -> target == COMPLETED || target == CANCELLED
            COMPLETED, CANCELLED -> false
        }
}

/**
 * Half-open time range backing a booking. The value object centralises
 * the validation rule (end strictly after start) and the equality /
 * `overlaps()` helpers so we don't sprinkle them around use cases.
 */
data class BookingTimeRange(
    val startsAt: OffsetDateTime,
    val endsAt: OffsetDateTime,
) {
    init {
        require(endsAt.isAfter(startsAt)) {
            "endsAt must be strictly after startsAt"
        }
    }

    /** Overlap test using the half-open [start, end) semantics. */
    fun overlaps(other: BookingTimeRange): Boolean = startsAt.isBefore(other.endsAt) && other.startsAt.isBefore(endsAt)
}

/**
 * Fields the application supplies when creating a fresh booking. The
 * DB defaults `status` to SCHEDULED and stamps the timestamps.
 */
data class NewBooking(
    val id: UUID,
    val operatingRoomId: UUID,
    val surgeonId: UUID,
    val createdById: UUID,
    val startsAt: OffsetDateTime,
    val endsAt: OffsetDateTime,
    val opType: String,
    val patientRef: String,
    val notes: String? = null,
) {
    init {
        require(opType.isNotBlank() && opType.length <= Booking.MAX_OP_TYPE) {
            "opType must be 1..${Booking.MAX_OP_TYPE} characters"
        }
        require(Booking.PATIENT_REF_REGEX.matches(patientRef)) {
            "patientRef must match ${Booking.PATIENT_REF_PATTERN}"
        }
        require(endsAt.isAfter(startsAt)) {
            "endsAt must be strictly after startsAt"
        }
    }
}

/**
 * Partial update applied via PATCH. Times move together (you can change
 * one or both — the repo enforces the resulting `endsAt > startsAt`),
 * status changes are NOT part of the patch — they go through
 * dedicated transition endpoints (cancel / start / complete) so the
 * lifecycle invariant is centrally enforced.
 */
data class BookingPatch(
    val operatingRoomId: UUID? = null,
    val surgeonId: UUID? = null,
    val startsAt: OffsetDateTime? = null,
    val endsAt: OffsetDateTime? = null,
    val opType: String? = null,
    val notes: String? = null,
) {
    init {
        if (opType != null) {
            require(opType.isNotBlank() && opType.length <= Booking.MAX_OP_TYPE) {
                "opType must be 1..${Booking.MAX_OP_TYPE} characters"
            }
        }
    }

    val isNoOp: Boolean
        get() =
            operatingRoomId == null &&
                surgeonId == null &&
                startsAt == null &&
                endsAt == null &&
                opType == null &&
                notes == null
}
