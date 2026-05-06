package com.kliniq.api.schedule

import com.kliniq.api.booking.BookingDto
import com.kliniq.domain.or.OperatingRoomStatus
import com.kliniq.domain.schedule.DaySchedule
import java.time.LocalDate
import java.util.UUID

/**
 * Wire shape for the day-view. The IANA time zone is exposed so the SPA
 * renders booking times in the clinic's local zone without guessing.
 */
data class ScheduleDto(
    val date: LocalDate,
    val timezone: String,
    val operatingRooms: List<OperatingRoomScheduleDto>,
) {
    companion object {
        fun of(schedule: DaySchedule): ScheduleDto =
            ScheduleDto(
                date = schedule.date,
                timezone = schedule.timezone.id,
                operatingRooms =
                    schedule.operatingRooms.map { entry ->
                        OperatingRoomScheduleDto(
                            id = entry.operatingRoom.id,
                            code = entry.operatingRoom.code,
                            name = entry.operatingRoom.name,
                            status = entry.operatingRoom.status,
                            bookings = entry.bookings.map(BookingDto::of),
                        )
                    },
            )
    }
}

data class OperatingRoomScheduleDto(
    val id: UUID,
    val code: String,
    val name: String,
    val status: OperatingRoomStatus,
    val bookings: List<BookingDto>,
)
