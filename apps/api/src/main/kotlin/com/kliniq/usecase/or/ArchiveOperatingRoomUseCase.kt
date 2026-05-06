package com.kliniq.usecase.or

import com.kliniq.infra.audit.AuditEntry
import com.kliniq.infra.audit.AuditWriter
import com.kliniq.persistence.booking.BookingRepository
import com.kliniq.persistence.or.OperatingRoomRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Soft-delete an operating room: status = RETIRED. Refuses to retire a
 * room that still holds active (SCHEDULED / IN_PROGRESS) bookings — the
 * caller has to cancel or reassign them first. Already-RETIRED rooms
 * are reported via Result.AlreadyArchived rather than falsely succeeding.
 */
@Service
class ArchiveOperatingRoomUseCase(
    private val operatingRooms: OperatingRoomRepository,
    private val bookings: BookingRepository,
    private val auditWriter: AuditWriter,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Suppress("ReturnCount")
    fun archive(
        actorUserId: UUID,
        id: UUID,
    ): Result {
        val current = operatingRooms.findById(id) ?: return Result.NotFound
        if (current.isRetired) return Result.AlreadyArchived
        val activeCount = bookings.countActiveByOperatingRoom(id)
        if (activeCount > 0) return Result.HasActiveBookings(activeCount)

        if (!operatingRooms.archive(id)) {
            // Race: someone retired the room between our findById and archive().
            return Result.AlreadyArchived
        }

        auditWriter.record(
            AuditEntry(
                action = "operating_room.archived",
                entityType = "operating_room",
                entityId = id,
                actorUserId = actorUserId,
                before = mapOf("status" to current.status.name),
                after = mapOf("status" to "RETIRED"),
            ),
        )
        log.info("operating_room.archived: id={} by={}", id, actorUserId)
        return Result.Success
    }

    sealed interface Result {
        data object Success : Result

        data object NotFound : Result

        data object AlreadyArchived : Result

        /** [activeCount] is the number of SCHEDULED / IN_PROGRESS bookings still on the room. */
        data class HasActiveBookings(
            val activeCount: Long,
        ) : Result
    }
}
