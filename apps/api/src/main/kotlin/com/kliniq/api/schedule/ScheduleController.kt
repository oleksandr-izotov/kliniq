package com.kliniq.api.schedule

import com.kliniq.api.error.ApiErrorResponse
import com.kliniq.usecase.schedule.DayScheduleUseCase
import com.kliniq.usecase.schedule.WeekScheduleUseCase
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate
import java.util.UUID

@RestController
@RequestMapping("/api/v1/schedule")
class ScheduleController(
    private val dayScheduleUseCase: DayScheduleUseCase,
    private val weekScheduleUseCase: WeekScheduleUseCase,
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

    /**
     * Week-view: seven consecutive days of one OR's bookings starting at
     * [from]. The OR is named by id; the user picks it from the OR
     * listing on the SPA side, so MAINTENANCE / RETIRED rows return
     * their bookings the same way ACTIVE rooms do (the picker decided
     * what's visible, not the schedule).
     */
    @GetMapping("/week")
    fun week(
        @RequestParam(name = "operatingRoomId") operatingRoomId: UUID,
        @RequestParam(name = "from")
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        from: LocalDate,
    ): ResponseEntity<*> =
        when (val result = weekScheduleUseCase.week(operatingRoomId, from)) {
            is WeekScheduleUseCase.Result.Success ->
                ResponseEntity.ok(WeekScheduleDto.of(result.schedule))
            WeekScheduleUseCase.Result.OperatingRoomNotFound ->
                ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                    ApiErrorResponse(code = "OR_NOT_FOUND", message = "Operating room not found."),
                )
        }
}
