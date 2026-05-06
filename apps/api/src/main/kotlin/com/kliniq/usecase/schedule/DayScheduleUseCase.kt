package com.kliniq.usecase.schedule

import com.kliniq.domain.or.OperatingRoomStatus
import com.kliniq.domain.schedule.DaySchedule
import com.kliniq.domain.schedule.OrSchedule
import com.kliniq.persistence.booking.BookingRepository
import com.kliniq.persistence.clinic.ClinicSettingsRepository
import com.kliniq.persistence.or.OperatingRoomRepository
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.LocalDate

/**
 * Build the one-day schedule view for the SPA: every visible operating
 * room with its bookings starting on [date] in the clinic's time zone.
 *
 * "Visible" defaults to ACTIVE only. MAINTENANCE rooms can be opted in
 * via [includeMaintenance]; RETIRED rooms are always hidden because the
 * UI's day-view never has a reason to surface archived rooms.
 *
 * Bookings of any status are returned — the SPA renders cancelled and
 * completed slots greyed out alongside live ones, so the operator gets
 * the full picture of what happened on the day without a second
 * round-trip per OR.
 */
@Service
class DayScheduleUseCase(
    private val operatingRooms: OperatingRoomRepository,
    private val bookings: BookingRepository,
    private val clinicSettings: ClinicSettingsRepository,
    private val clock: Clock,
) {
    fun day(
        date: LocalDate? = null,
        includeMaintenance: Boolean = false,
    ): DaySchedule {
        val settings = clinicSettings.get()
        val resolvedDate = date ?: LocalDate.now(clock.withZone(settings.timezone))

        // Half-open `[date, date+1)` window in the clinic's local zone, then
        // converted to OffsetDateTime for the DB call so DST transitions on
        // the queried day don't double-count or skip a hour.
        val startOfDay = resolvedDate.atStartOfDay(settings.timezone).toOffsetDateTime()
        val endOfDay = resolvedDate.plusDays(1).atStartOfDay(settings.timezone).toOffsetDateTime()

        val visibleRooms =
            operatingRooms
                .listAll(includeRetired = false)
                .filter { room ->
                    when (room.status) {
                        OperatingRoomStatus.ACTIVE -> true
                        OperatingRoomStatus.MAINTENANCE -> includeMaintenance
                        OperatingRoomStatus.RETIRED -> false
                    }
                }

        // One batched query for every visible OR.
        val bookingsForDay =
            bookings
                .findByOperatingRoomsBetween(
                    operatingRoomIds = visibleRooms.map { it.id },
                    fromInclusive = startOfDay,
                    toExclusive = endOfDay,
                )
        val byRoomId = bookingsForDay.groupBy { it.operatingRoomId }

        return DaySchedule(
            date = resolvedDate,
            timezone = settings.timezone,
            operatingRooms =
                visibleRooms.map { room ->
                    OrSchedule(
                        operatingRoom = room,
                        bookings = byRoomId[room.id].orEmpty(),
                    )
                },
        )
    }
}
