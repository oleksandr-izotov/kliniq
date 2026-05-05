package com.kliniq.domain.clinic

import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId

/**
 * Clinic-wide settings stored as the singleton row in `clinic_settings`.
 * Day 22 adds the write side; Day 19 only needs to read working hours and
 * the time zone for booking-time validation.
 *
 * `timezone` is parsed at the persistence boundary so this object always
 * holds a valid [ZoneId]. A bad string in the DB (shouldn't happen given
 * the V3 seed and the future write-side validation) surfaces as a
 * DateTimeException on load — better than a corrupt booking flow.
 */
data class ClinicSettings(
    val name: String,
    val timezone: ZoneId,
    val workingHoursStart: LocalTime,
    val workingHoursEnd: LocalTime,
    val defaultBookingMinutes: Int,
    val updatedAt: OffsetDateTime,
) {
    init {
        require(name.isNotBlank() && name.length <= MAX_NAME) {
            "name must be 1..$MAX_NAME characters"
        }
        require(workingHoursEnd.isAfter(workingHoursStart)) {
            "workingHoursEnd must be strictly after workingHoursStart"
        }
        require(defaultBookingMinutes in MIN_DEFAULT_BOOKING..MAX_DEFAULT_BOOKING) {
            "defaultBookingMinutes must be $MIN_DEFAULT_BOOKING..$MAX_DEFAULT_BOOKING"
        }
    }

    companion object {
        const val MAX_NAME = 100
        const val MIN_DEFAULT_BOOKING = 5
        const val MAX_DEFAULT_BOOKING = 1_440 // 24 hours
    }
}
