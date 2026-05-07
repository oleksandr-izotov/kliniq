package com.kliniq.usecase.booking

import com.kliniq.domain.booking.Booking
import com.kliniq.domain.booking.BookingPatch
import com.kliniq.domain.booking.BookingStatus
import com.kliniq.domain.booking.BookingTimeRange
import com.kliniq.domain.or.OperatingRoomStatus
import com.kliniq.infra.audit.AuditEntry
import com.kliniq.infra.audit.AuditWriter
import com.kliniq.infra.realtime.BookingEvent
import com.kliniq.infra.realtime.BookingEventKind
import com.kliniq.infra.realtime.BookingEventPublisher
import com.kliniq.persistence.booking.BookingRepository
import com.kliniq.persistence.clinic.ClinicSettingsRepository
import com.kliniq.persistence.or.OperatingRoomRepository
import com.kliniq.persistence.user.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Apply a partial update to an existing booking. Status changes are NOT
 * part of the patch — those go through the dedicated transition endpoints
 * so the lifecycle invariant has one enforcement site.
 *
 * Re-runs every cross-aggregate guard from CreateBookingUseCase against
 * the merged values: a PATCH that moves the booking into another OR or
 * to a different time slot is held to the same standard as a fresh
 * create. The pre-check overlap query passes the booking's own id as
 * `excludingId` so it doesn't self-conflict; the EXCLUDE constraint
 * remains the source of truth under concurrent writes.
 */
@Service
@Suppress("LongParameterList") // orchestration use case wires many collaborators
class UpdateBookingUseCase(
    private val bookings: BookingRepository,
    private val operatingRooms: OperatingRoomRepository,
    private val users: UserRepository,
    private val clinicSettings: ClinicSettingsRepository,
    private val auditWriter: AuditWriter,
    private val eventPublisher: BookingEventPublisher,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Suppress("ReturnCount", "LongMethod", "CyclomaticComplexMethod") // ceremony of validation guards
    fun update(
        actorUserId: UUID,
        id: UUID,
        patch: BookingPatch,
    ): Result {
        val before = bookings.findById(id) ?: return Result.NotFound
        // Terminal states are immutable — once cancelled or completed, the row
        // freezes for audit purposes.
        if (before.status == BookingStatus.CANCELLED || before.status == BookingStatus.COMPLETED) {
            return Result.TerminalState(before.status)
        }
        if (patch.isNoOp) return Result.Success(before)

        // Compute the merged booking (without writing yet) so we can validate.
        val newOperatingRoomId = patch.operatingRoomId ?: before.operatingRoomId
        val newSurgeonId = patch.surgeonId ?: before.surgeonId
        val newStartsAt = patch.startsAt ?: before.startsAt
        val newEndsAt = patch.endsAt ?: before.endsAt
        if (!newEndsAt.isAfter(newStartsAt)) return Result.InvalidInput("endsAt must be strictly after startsAt")

        // Surgeon changed? Re-validate.
        if (patch.surgeonId != null) {
            val surgeon = users.findById(newSurgeonId) ?: return Result.SurgeonNotFound
            if (!surgeon.isSurgeon) return Result.NotASurgeon
            if (!surgeon.isActive) return Result.SurgeonInactive
        }

        // OR changed? Re-validate.
        if (patch.operatingRoomId != null) {
            val operatingRoom = operatingRooms.findById(newOperatingRoomId) ?: return Result.OperatingRoomNotFound
            if (operatingRoom.status != OperatingRoomStatus.ACTIVE) return Result.OperatingRoomInactive
        }

        // Working-hours check, if either endpoint moved.
        if (patch.startsAt != null || patch.endsAt != null) {
            val settings = clinicSettings.get()
            val startLocal = newStartsAt.atZoneSameInstant(settings.timezone).toLocalTime()
            val endLocal = newEndsAt.atZoneSameInstant(settings.timezone).toLocalTime()
            val sameDay =
                newStartsAt.atZoneSameInstant(settings.timezone).toLocalDate() ==
                    newEndsAt.atZoneSameInstant(settings.timezone).toLocalDate()
            if (!sameDay ||
                startLocal.isBefore(settings.workingHoursStart) ||
                endLocal.isAfter(settings.workingHoursEnd)
            ) {
                return Result.OutsideWorkingHours
            }
        }

        // Pre-check overlap with the new range/OR, excluding the row being patched.
        val preCheck =
            bookings
                .findActiveOverlapping(
                    operatingRoomId = newOperatingRoomId,
                    range = BookingTimeRange(newStartsAt, newEndsAt),
                    excludingId = id,
                ).firstOrNull()
        if (preCheck != null) return Result.BookingConflict(preCheck.id)

        val after =
            try {
                bookings.update(id, patch) ?: return Result.NotFound
            } catch (_: DataIntegrityViolationException) {
                log.warn("booking.update: EXCLUDE race on booking {}", id)
                return Result.BookingConflict(occludingBookingId = null)
            } catch (_: IllegalArgumentException) {
                return Result.InvalidInput("rejected by domain validation")
            }

        auditWriter.record(
            AuditEntry(
                action = "booking.updated",
                entityType = "booking",
                entityId = id,
                actorUserId = actorUserId,
                before =
                    mapOf(
                        "operatingRoomId" to before.operatingRoomId.toString(),
                        "surgeonId" to before.surgeonId.toString(),
                        "startsAt" to before.startsAt.toString(),
                        "endsAt" to before.endsAt.toString(),
                        "opType" to before.opType,
                    ),
                after =
                    mapOf(
                        "operatingRoomId" to after.operatingRoomId.toString(),
                        "surgeonId" to after.surgeonId.toString(),
                        "startsAt" to after.startsAt.toString(),
                        "endsAt" to after.endsAt.toString(),
                        "opType" to after.opType,
                    ),
            ),
        )
        log.info("booking.updated: id={} by={}", id, actorUserId)
        eventPublisher.publish(
            BookingEvent(
                kind = BookingEventKind.UPDATED,
                bookingId = after.id,
                operatingRoomId = after.operatingRoomId,
                occurredAt = after.updatedAt,
            ),
        )
        return Result.Success(after)
    }

    sealed interface Result {
        data class Success(
            val booking: Booking,
        ) : Result

        data object NotFound : Result

        /** Booking is in a terminal status (CANCELLED / COMPLETED) and can't be patched. */
        data class TerminalState(
            val status: BookingStatus,
        ) : Result

        data class InvalidInput(
            val reason: String,
        ) : Result

        data object SurgeonNotFound : Result

        data object NotASurgeon : Result

        data object SurgeonInactive : Result

        data object OperatingRoomNotFound : Result

        data object OperatingRoomInactive : Result

        data object OutsideWorkingHours : Result

        data class BookingConflict(
            val occludingBookingId: UUID?,
        ) : Result
    }
}
