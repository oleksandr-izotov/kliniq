package com.kliniq.usecase.booking

import com.github.f4b6a3.uuid.UuidCreator
import com.kliniq.domain.booking.Booking
import com.kliniq.domain.booking.BookingTimeRange
import com.kliniq.domain.booking.NewBooking
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
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Create a new booking with all the cross-aggregate validation V1 needs:
 *   - surgeon exists and is_surgeon=true
 *   - operating room exists and status=ACTIVE
 *   - time range falls within the clinic's working hours (in clinic TZ)
 *   - no other ACTIVE booking already overlaps the slot
 *
 * The pre-check overlap query gives a friendly conflict response with
 * the occluding booking id; the EXCLUDE constraint is the actual source
 * of truth and catches the race where two concurrent creators both pass
 * pre-check and one of them races into the same slot. We surface that
 * race as the same `BookingConflict` result so the SPA renders one
 * consistent UX.
 *
 * PII discipline: `patient_ref` never appears in audit metadata or logs.
 * Audit metadata captures the operational shape (room, surgeon, range).
 */
@Service
@Suppress("LongParameterList") // orchestration use case wires many collaborators
class CreateBookingUseCase(
    private val bookings: BookingRepository,
    private val operatingRooms: OperatingRoomRepository,
    private val users: UserRepository,
    private val clinicSettings: ClinicSettingsRepository,
    private val auditWriter: AuditWriter,
    private val eventPublisher: BookingEventPublisher,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    data class Command(
        val operatingRoomId: UUID,
        val surgeonId: UUID,
        val createdById: UUID,
        val startsAt: OffsetDateTime,
        val endsAt: OffsetDateTime,
        val opType: String,
        val patientRef: String,
        val notes: String?,
    )

    @Suppress("ReturnCount", "LongMethod") // ceremony of validation guards
    fun create(cmd: Command): Result {
        // 1. Domain init runs invariants we duplicate from the DB CHECKs.
        val draft =
            try {
                NewBooking(
                    id = UuidCreator.getTimeOrderedEpoch(),
                    operatingRoomId = cmd.operatingRoomId,
                    surgeonId = cmd.surgeonId,
                    createdById = cmd.createdById,
                    startsAt = cmd.startsAt,
                    endsAt = cmd.endsAt,
                    opType = cmd.opType.trim(),
                    patientRef = cmd.patientRef.trim(),
                    notes = cmd.notes?.trim()?.takeIf { it.isNotEmpty() },
                )
            } catch (e: IllegalArgumentException) {
                return Result.InvalidInput(e.message ?: "invalid booking")
            }

        // 2. Surgeon must exist AND be a surgeon.
        val surgeon = users.findById(cmd.surgeonId) ?: return Result.SurgeonNotFound
        if (!surgeon.isSurgeon) return Result.NotASurgeon
        if (!surgeon.isActive) return Result.SurgeonInactive

        // 3. OR must exist AND be ACTIVE (MAINTENANCE / RETIRED don't take new bookings).
        val operatingRoom = operatingRooms.findById(cmd.operatingRoomId) ?: return Result.OperatingRoomNotFound
        if (operatingRoom.status != OperatingRoomStatus.ACTIVE) return Result.OperatingRoomInactive

        // 4. Time range within working hours, in the clinic's local zone.
        val settings = clinicSettings.get()
        val startLocal = cmd.startsAt.atZoneSameInstant(settings.timezone).toLocalTime()
        val endLocal = cmd.endsAt.atZoneSameInstant(settings.timezone).toLocalTime()
        val sameDay =
            cmd.startsAt.atZoneSameInstant(settings.timezone).toLocalDate() ==
                cmd.endsAt.atZoneSameInstant(settings.timezone).toLocalDate()
        if (!sameDay ||
            startLocal.isBefore(settings.workingHoursStart) ||
            endLocal.isAfter(settings.workingHoursEnd)
        ) {
            return Result.OutsideWorkingHours
        }

        // 5. Friendly pre-check — name the occluding booking before we attempt
        // the INSERT. This isn't a substitute for the EXCLUDE constraint; it's
        // a UX shortcut that the constraint will retroactively confirm.
        val preCheckOverlap =
            bookings
                .findActiveOverlapping(
                    operatingRoomId = cmd.operatingRoomId,
                    range = BookingTimeRange(cmd.startsAt, cmd.endsAt),
                    excludingId = null,
                ).firstOrNull()
        if (preCheckOverlap != null) {
            return Result.BookingConflict(occludingBookingId = preCheckOverlap.id)
        }

        // 6. INSERT. The EXCLUDE constraint is what makes this safe under
        // concurrent writers — a race that beat us to the slot fires
        // DataIntegrityViolationException, which we map to the same
        // BookingConflict envelope (without the occluding id, since we
        // already lost the pre-check race and another lookup is racy too).
        val saved =
            try {
                bookings.create(draft)
            } catch (_: DataIntegrityViolationException) {
                log.warn(
                    "booking.create: EXCLUDE race on OR {} for [{}, {})",
                    cmd.operatingRoomId,
                    cmd.startsAt,
                    cmd.endsAt,
                )
                return Result.BookingConflict(occludingBookingId = null)
            }

        // 7. Audit. patient_ref intentionally absent — see PII discipline.
        auditWriter.record(
            AuditEntry(
                action = "booking.created",
                entityType = "booking",
                entityId = saved.id,
                actorUserId = cmd.createdById,
                after =
                    mapOf(
                        "operatingRoomId" to saved.operatingRoomId.toString(),
                        "surgeonId" to saved.surgeonId.toString(),
                        "startsAt" to saved.startsAt.toString(),
                        "endsAt" to saved.endsAt.toString(),
                        "opType" to saved.opType,
                    ),
            ),
        )
        log.info(
            "booking.created: id={} or={} surgeon={} by={}",
            saved.id,
            saved.operatingRoomId,
            saved.surgeonId,
            cmd.createdById,
        )

        // 8. Real-time fan-out. Best-effort by design; the audit row above
        // is the durable source of truth, the SSE event is just the
        // courtesy push to connected browsers.
        eventPublisher.publish(
            BookingEvent(
                kind = BookingEventKind.CREATED,
                bookingId = saved.id,
                operatingRoomId = saved.operatingRoomId,
                occurredAt = saved.updatedAt,
            ),
        )
        return Result.Success(saved)
    }

    sealed interface Result {
        data class Success(
            val booking: Booking,
        ) : Result

        /** Domain-level rejection (length, pattern, range). [reason] is internal-only. */
        data class InvalidInput(
            val reason: String,
        ) : Result

        data object SurgeonNotFound : Result

        data object NotASurgeon : Result

        data object SurgeonInactive : Result

        data object OperatingRoomNotFound : Result

        data object OperatingRoomInactive : Result

        data object OutsideWorkingHours : Result

        /**
         * Active booking overlaps the requested slot. [occludingBookingId] is
         * present when the pre-check found it; null when the EXCLUDE
         * constraint fired during the INSERT (race) — in that case the
         * caller can retry the lookup if they need the occluder's id.
         */
        data class BookingConflict(
            val occludingBookingId: UUID?,
        ) : Result
    }
}
