package com.kliniq.api.schedule

import com.kliniq.usecase.schedule.DayScheduleUseCase
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate

@RestController
@RequestMapping("/api/v1/schedule")
class ScheduleController(
    private val dayScheduleUseCase: DayScheduleUseCase,
) {
    /**
     * Day-view: every visible operating room with its bookings on [date].
     * Defaults to today in the clinic's local zone when [date] is omitted.
     * MAINTENANCE rooms are hidden by default; the SPA opts in via
     * `?includeMaintenance=true` when an admin needs the full picture.
     */
    @GetMapping
    fun day(
        @RequestParam(required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        date: LocalDate?,
        @RequestParam(name = "includeMaintenance", defaultValue = "false") includeMaintenance: Boolean,
    ): ScheduleDto = ScheduleDto.of(dayScheduleUseCase.day(date = date, includeMaintenance = includeMaintenance))
}
