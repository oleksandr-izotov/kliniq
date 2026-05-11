package com.kliniq.api.clinic

import com.kliniq.api.error.ApiErrorResponse
import com.kliniq.domain.clinic.ClinicSettings
import com.kliniq.infra.security.KliniqAuthentication
import com.kliniq.persistence.clinic.ClinicSettingsRepository
import com.kliniq.usecase.clinic.UpdateClinicSettingsUseCase
import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Size
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

@RestController
@RequestMapping("/api/v1/clinic/settings")
class ClinicSettingsController(
    private val repository: ClinicSettingsRepository,
    private val updateUseCase: UpdateClinicSettingsUseCase,
) {
    @GetMapping
    fun get(): ClinicSettingsDto = ClinicSettingsDto.of(repository.get())

    /**
     * Mark onboarding complete. Idempotent: re-calling after `onboardedAt`
     * is already stamped returns the existing settings unchanged. The wizard
     * fires this on its "Done" step after collecting clinic name / timezone /
     * hours via PATCH above (and optionally an OR / invitation via the
     * respective endpoints).
     */
    @PostMapping("/onboard")
    fun onboard(): ClinicSettingsDto =
        ClinicSettingsDto.of(
            repository.markOnboardedIfUnset(OffsetDateTime.now(ZoneOffset.UTC)),
        )

    @PatchMapping
    fun update(
        @Valid @RequestBody request: UpdateClinicSettingsRequest,
    ): ResponseEntity<*> =
        when (
            val result =
                updateUseCase.update(
                    actorUserId = currentUserId(),
                    cmd =
                        UpdateClinicSettingsUseCase.Command(
                            name = request.name,
                            timezone = request.timezone,
                            workingHoursStart = request.workingHoursStart,
                            workingHoursEnd = request.workingHoursEnd,
                            defaultBookingMinutes = request.defaultBookingMinutes,
                        ),
                )
        ) {
            is UpdateClinicSettingsUseCase.Result.Success ->
                ResponseEntity.ok(ClinicSettingsDto.of(result.settings))
            is UpdateClinicSettingsUseCase.Result.InvalidTimezone ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                    ApiErrorResponse(
                        code = "INVALID_TIMEZONE",
                        message = "\"${result.supplied}\" is not a valid IANA time zone.",
                    ),
                )
            is UpdateClinicSettingsUseCase.Result.InvalidInput ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                    ApiErrorResponse(code = "VALIDATION_ERROR", message = result.reason),
                )
        }

    private fun currentUserId(): UUID =
        (SecurityContextHolder.getContext().authentication as? KliniqAuthentication)
            ?.user
            ?.id
            ?: error("Authenticated endpoint reached without KliniqAuthentication")
}

data class ClinicSettingsDto(
    val name: String,
    val timezone: String,
    val workingHoursStart: LocalTime,
    val workingHoursEnd: LocalTime,
    val defaultBookingMinutes: Int,
    /** Non-null when the onboarding wizard has been completed. */
    val onboardedAt: OffsetDateTime?,
) {
    companion object {
        fun of(settings: ClinicSettings): ClinicSettingsDto =
            ClinicSettingsDto(
                name = settings.name,
                timezone = settings.timezone.id,
                workingHoursStart = settings.workingHoursStart,
                workingHoursEnd = settings.workingHoursEnd,
                defaultBookingMinutes = settings.defaultBookingMinutes,
                onboardedAt = settings.onboardedAt,
            )
    }
}

data class UpdateClinicSettingsRequest(
    @field:Size(min = 1, max = MAX_NAME)
    val name: String? = null,
    @field:Size(min = 1, max = MAX_TIMEZONE)
    val timezone: String? = null,
    @field:DateTimeFormat(iso = DateTimeFormat.ISO.TIME)
    val workingHoursStart: LocalTime? = null,
    @field:DateTimeFormat(iso = DateTimeFormat.ISO.TIME)
    val workingHoursEnd: LocalTime? = null,
    @field:Min(MIN_DEFAULT_BOOKING.toLong())
    val defaultBookingMinutes: Int? = null,
) {
    companion object {
        const val MAX_NAME = 100
        const val MAX_TIMEZONE = 64
        const val MIN_DEFAULT_BOOKING = 5
    }
}
