package com.kliniq.persistence.booking

import com.kliniq.db.tables.records.BookingsRecord
import com.kliniq.db.tables.references.BOOKINGS
import com.kliniq.domain.booking.Booking
import com.kliniq.domain.booking.BookingPatch
import com.kliniq.domain.booking.BookingStatus
import com.kliniq.domain.booking.BookingTimeRange
import com.kliniq.domain.booking.NewBooking
import org.jooq.DSLContext
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.util.UUID

@Repository
class JooqBookingRepository(
    private val dsl: DSLContext,
) : BookingRepository {
    override fun create(newBooking: NewBooking): Booking {
        val record =
            dsl
                .insertInto(BOOKINGS)
                .set(BOOKINGS.ID, newBooking.id)
                .set(BOOKINGS.OPERATING_ROOM_ID, newBooking.operatingRoomId)
                .set(BOOKINGS.SURGEON_ID, newBooking.surgeonId)
                .set(BOOKINGS.CREATED_BY_ID, newBooking.createdById)
                .set(BOOKINGS.STARTS_AT, newBooking.startsAt)
                .set(BOOKINGS.ENDS_AT, newBooking.endsAt)
                .set(BOOKINGS.OP_TYPE, newBooking.opType)
                .set(BOOKINGS.PATIENT_REF, newBooking.patientRef)
                .set(BOOKINGS.NOTES, newBooking.notes?.takeIf { it.isNotBlank() })
                // status defaults SCHEDULED at the DB layer
                .returning()
                .fetchOne() ?: error("INSERT into bookings returned no row for id=${newBooking.id}")
        return record.toDomain()
    }

    override fun findById(id: UUID): Booking? =
        dsl
            .selectFrom(BOOKINGS)
            .where(BOOKINGS.ID.eq(id))
            .fetchOne()
            ?.toDomain()

    override fun findActiveOverlapping(
        operatingRoomId: UUID,
        range: BookingTimeRange,
        excludingId: UUID?,
    ): List<Booking> {
        // Half-open overlap: A=[a1,a2) ∩ B=[b1,b2) ≠ ∅  ⟺  a1 < b2 AND a2 > b1.
        // The single tstzrange index supporting bookings_no_overlap also covers
        // this query, so it stays cheap even at thousands of bookings per OR.
        var condition =
            BOOKINGS.OPERATING_ROOM_ID
                .eq(operatingRoomId)
                .and(BOOKINGS.STATUS.`in`(BookingStatus.SCHEDULED.name, BookingStatus.IN_PROGRESS.name))
                .and(BOOKINGS.STARTS_AT.lt(range.endsAt))
                .and(BOOKINGS.ENDS_AT.gt(range.startsAt))
        if (excludingId != null) condition = condition.and(BOOKINGS.ID.ne(excludingId))
        return dsl
            .selectFrom(BOOKINGS)
            .where(condition)
            .orderBy(BOOKINGS.STARTS_AT.asc())
            .fetch()
            .map { it.toDomain() }
    }

    override fun findByOperatingRoomBetween(
        operatingRoomId: UUID,
        fromInclusive: OffsetDateTime,
        toExclusive: OffsetDateTime,
    ): List<Booking> =
        dsl
            .selectFrom(BOOKINGS)
            .where(BOOKINGS.OPERATING_ROOM_ID.eq(operatingRoomId))
            .and(BOOKINGS.STARTS_AT.ge(fromInclusive))
            .and(BOOKINGS.STARTS_AT.lt(toExclusive))
            .orderBy(BOOKINGS.STARTS_AT.asc())
            .fetch()
            .map { it.toDomain() }

    override fun countActiveByOperatingRoom(operatingRoomId: UUID): Long =
        dsl
            .fetchCount(
                BOOKINGS,
                BOOKINGS.OPERATING_ROOM_ID
                    .eq(operatingRoomId)
                    .and(BOOKINGS.STATUS.`in`(BookingStatus.SCHEDULED.name, BookingStatus.IN_PROGRESS.name)),
            ).toLong()

    /**
     * Read-modify-write inside a transaction. Letting the EXCLUDE constraint
     * rule on the merged values is the source of truth for conflict detection;
     * use cases pre-check and surface a friendly error, but the constraint is
     * what guarantees correctness under concurrent updates.
     */
    @Transactional
    @Suppress("ReturnCount") // each branch is one informative early exit
    override fun update(
        id: UUID,
        patch: BookingPatch,
    ): Booking? {
        val current = findById(id) ?: return null
        if (patch.isNoOp) return current

        val newRoom = patch.operatingRoomId ?: current.operatingRoomId
        val newSurgeon = patch.surgeonId ?: current.surgeonId
        val newStart = patch.startsAt ?: current.startsAt
        val newEnd = patch.endsAt ?: current.endsAt
        val newOpType = patch.opType ?: current.opType
        // Treat blank notes as "clear".
        val newNotes =
            when {
                patch.notes == null -> current.notes
                patch.notes.isBlank() -> null
                else -> patch.notes
            }
        require(newEnd.isAfter(newStart)) {
            "endsAt must be strictly after startsAt after patch"
        }

        val updated =
            dsl
                .update(BOOKINGS)
                .set(BOOKINGS.OPERATING_ROOM_ID, newRoom)
                .set(BOOKINGS.SURGEON_ID, newSurgeon)
                .set(BOOKINGS.STARTS_AT, newStart)
                .set(BOOKINGS.ENDS_AT, newEnd)
                .set(BOOKINGS.OP_TYPE, newOpType)
                .set(BOOKINGS.NOTES, newNotes)
                .where(BOOKINGS.ID.eq(id))
                .execute()
        return if (updated == 1) findById(id) else null
    }

    override fun transitionStatus(
        id: UUID,
        expected: BookingStatus,
        target: BookingStatus,
    ): Booking? {
        val updated =
            dsl
                .update(BOOKINGS)
                .set(BOOKINGS.STATUS, target.name)
                .where(BOOKINGS.ID.eq(id))
                .and(BOOKINGS.STATUS.eq(expected.name))
                .execute()
        return if (updated == 1) findById(id) else null
    }
}

private fun BookingsRecord.toDomain(): Booking =
    Booking(
        id = requireNotNull(id) { "bookings.id is NOT NULL but record produced null" },
        operatingRoomId = requireNotNull(operatingRoomId),
        surgeonId = requireNotNull(surgeonId),
        createdById = requireNotNull(createdById),
        startsAt = requireNotNull(startsAt),
        endsAt = requireNotNull(endsAt),
        opType = requireNotNull(opType),
        patientRef = requireNotNull(patientRef),
        status = BookingStatus.valueOf(requireNotNull(status)),
        notes = notes,
        createdAt = requireNotNull(createdAt),
        updatedAt = requireNotNull(updatedAt),
    )
