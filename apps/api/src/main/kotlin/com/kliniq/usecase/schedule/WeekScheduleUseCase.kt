package com.kliniq.usecase.schedule

import com.kliniq.domain.schedule.DayBookings
import com.kliniq.domain.schedule.WeekSchedule
import com.kliniq.persistence.booking.BookingRepository
import com.kliniq.persistence.clinic.ClinicSettingsRepository
import com.kliniq.persistence.or.OperatingRoomRepository
import org.springframework.stereotype.Service
import java.time.LocalDate
import java.util.UUID

/**
 * Seven days of a single OR's schedule for the week-view SPA. Reuses
 * [BookingRepository.findByOperatingRoomsBetween] so we still issue
 * exactly one query for the week (single-element OR list, half-open
 * `[from, from+7d)` window in the clinic zone — same DST-safe shape
 * the day-view uses).
 *
 * The OR's `status` isn't filtered: the user explicitly picks the OR
 * via the picker, so a MAINTENANCE or RETIRED row is something they
 * asked to see. Day-view's status filter exists because day-view
 * lists every OR; week-view is OR-centric.
 */
@Service
class WeekScheduleUseCase(
    private val operatingRooms: OperatingRoomRepository,
    private val bookings: BookingRepository,
    private val clinicSettings: ClinicSettingsRepository,
) {
    @Suppress("ReturnCount")
    fun week(
        operatingRoomId: UUID,
        from: LocalDate,
    ): Result {
        val operatingRoom = operatingRooms.findById(operatingRoomId) ?: return Result.OperatingRoomNotFound
        val settings = clinicSettings.get()

        val windowStart = from.atStartOfDay(settings.timezone).toOffsetDateTime()
        val windowEnd = from.plusDays(WEEK_LENGTH.toLong()).atStartOfDay(settings.timezone).toOffsetDateTime()

        val rows =
            bookings.findByOperatingRoomsBetween(
                operatingRoomIds = listOf(operatingRoomId),
                fromInclusive = windowStart,
                toExclusive = windowEnd,
            )
        // Group by the clinic-local date a booking *starts* on. Bookings
        // that span midnight (we don't allow them today, but the schema
        // doesn't forbid them either) land on the day they begin.
        val byDate =
            rows.groupBy { it.startsAt.atZoneSameInstant(settings.timezone).toLocalDate() }

        val days =
            (0 until WEEK_LENGTH).map { offset ->
                val day = from.plusDays(offset.toLong())
                DayBookings(date = day, bookings = byDate[day].orEmpty())
            }
        return Result.Success(
            WeekSchedule(
                operatingRoom = operatingRoom,
                timezone = settings.timezone,
                from = from,
                days = days,
            ),
        )
    }

    sealed interface Result {
        data class Success(
            val schedule: WeekSchedule,
        ) : Result

        data object OperatingRoomNotFound : Result
    }

    companion object {
        const val WEEK_LENGTH: Int = 7
    }
}
