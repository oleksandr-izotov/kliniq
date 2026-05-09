package com.kliniq.domain.schedule

import com.kliniq.domain.booking.Booking
import com.kliniq.domain.or.OperatingRoom
import java.time.LocalDate
import java.time.ZoneId

/**
 * Pre-grouped one-day view of every visible operating room and its
 * bookings. The use case computes this against the clinic's local time
 * zone — `[date, date+1)` in the clinic zone — so a UI rendering for the
 * date 2026-07-01 sees every booking that starts on that calendar day,
 * regardless of UTC offset.
 *
 * `operatingRooms` is ordered by `code` (matches the listing endpoint);
 * each [OrSchedule.bookings] is ordered by `startsAt`. Cancelled and
 * completed bookings are included so the SPA can grey them out without
 * a second round-trip.
 */
data class DaySchedule(
    val date: LocalDate,
    val timezone: ZoneId,
    val operatingRooms: List<OrSchedule>,
)

data class OrSchedule(
    val operatingRoom: OperatingRoom,
    val bookings: List<Booking>,
)

/**
 * Seven consecutive days of one operating room's schedule. The week is
 * always exactly 7 entries, starting from [from], computed in the
 * clinic's local zone — so a `from = 2026-07-01` request always returns
 * 2026-07-01 through 2026-07-07 regardless of viewer.
 *
 * Like [DaySchedule], cancelled and completed bookings are included; the
 * SPA greys them out client-side.
 */
data class WeekSchedule(
    val operatingRoom: OperatingRoom,
    val timezone: ZoneId,
    val from: LocalDate,
    val days: List<DayBookings>,
)

data class DayBookings(
    val date: LocalDate,
    val bookings: List<Booking>,
)
