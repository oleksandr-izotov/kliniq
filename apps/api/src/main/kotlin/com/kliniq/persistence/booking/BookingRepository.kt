package com.kliniq.persistence.booking

import com.kliniq.domain.booking.Booking
import com.kliniq.domain.booking.BookingPatch
import com.kliniq.domain.booking.BookingStatus
import com.kliniq.domain.booking.BookingTimeRange
import com.kliniq.domain.booking.NewBooking
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Persistence-layer port for [Booking]. The repository is the single place
 * the bookings_no_overlap EXCLUDE constraint is observed: implementations
 * must let the underlying `DataIntegrityViolationException` (or its
 * subtype) propagate so the use-case layer can disambiguate "exclusion
 * collision" from generic FK / NOT NULL violations and turn it into a
 * clean 409 BOOKING_CONFLICT.
 */
interface BookingRepository {
    fun create(newBooking: NewBooking): Booking

    fun findById(id: UUID): Booking?

    /** Apply zero or more filters; ordered by `starts_at` ascending. */
    fun findFiltered(filter: BookingFilter): List<Booking>

    /**
     * Active bookings (status IN SCHEDULED, IN_PROGRESS) on [operatingRoomId]
     * whose half-open time range overlaps [range]. Optionally exclude one
     * booking id (used by PATCH to skip the row being updated).
     */
    fun findActiveOverlapping(
        operatingRoomId: UUID,
        range: BookingTimeRange,
        excludingId: UUID? = null,
    ): List<Booking>

    /**
     * Any booking on [operatingRoomId] whose start falls in `[from, to)`,
     * regardless of status. The schedule day-view uses this to render
     * cancelled / completed slots greyed out alongside live bookings.
     */
    fun findByOperatingRoomBetween(
        operatingRoomId: UUID,
        fromInclusive: OffsetDateTime,
        toExclusive: OffsetDateTime,
    ): List<Booking>

    /**
     * Same window semantics as [findByOperatingRoomBetween] but across many
     * rooms in one query — avoids N+1 when the schedule day-view fans out
     * across every room in the clinic.
     */
    fun findByOperatingRoomsBetween(
        operatingRoomIds: Collection<UUID>,
        fromInclusive: OffsetDateTime,
        toExclusive: OffsetDateTime,
    ): List<Booking>

    /**
     * Count of bookings on [operatingRoomId] that still hold a slot
     * (SCHEDULED or IN_PROGRESS). Used by the OR archive flow to refuse
     * archiving a room with live bookings.
     */
    fun countActiveByOperatingRoom(operatingRoomId: UUID): Long

    fun update(
        id: UUID,
        patch: BookingPatch,
    ): Booking?

    /**
     * Atomic compare-and-set on status. Returns the refreshed row when
     * the row existed AND its current status was [expected]; returns null
     * otherwise. Use cases consult [BookingStatus.canTransitionTo] before
     * calling so the [expected] argument matches lifecycle rules.
     */
    fun transitionStatus(
        id: UUID,
        expected: BookingStatus,
        target: BookingStatus,
    ): Booking?
}

/**
 * Optional filters for [BookingRepository.findFiltered]. `null` on a field
 * means "skip this predicate". `from` / `to` are matched against
 * `starts_at` (the schedule view's natural sort key).
 */
data class BookingFilter(
    val operatingRoomId: UUID? = null,
    val surgeonId: UUID? = null,
    val fromInclusive: OffsetDateTime? = null,
    val toExclusive: OffsetDateTime? = null,
    val status: BookingStatus? = null,
)
