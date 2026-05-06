package com.kliniq.usecase.clinic

import com.kliniq.domain.clinic.ClinicSettings
import com.kliniq.infra.audit.AuditEntry
import com.kliniq.infra.audit.AuditWriter
import com.kliniq.persistence.clinic.ClinicSettingsPatch
import com.kliniq.persistence.clinic.ClinicSettingsRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.LocalTime
import java.time.ZoneId
import java.time.zone.ZoneRulesException
import java.util.UUID

/**
 * Update the singleton clinic-settings row. ADMIN only — gated in
 * SecurityConfig. The string-typed timezone passed by the SPA is parsed
 * here so an invalid IANA name surfaces as a clean 400 instead of a
 * generic DB-layer error.
 */
@Service
class UpdateClinicSettingsUseCase(
    private val repository: ClinicSettingsRepository,
    private val auditWriter: AuditWriter,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    data class Command(
        val name: String? = null,
        val timezone: String? = null,
        val workingHoursStart: LocalTime? = null,
        val workingHoursEnd: LocalTime? = null,
        val defaultBookingMinutes: Int? = null,
    )

    @Suppress("ReturnCount") // each branch is one informative early exit
    fun update(
        actorUserId: UUID,
        cmd: Command,
    ): Result {
        val parsedZone =
            if (cmd.timezone != null) {
                try {
                    ZoneId.of(cmd.timezone.trim())
                } catch (_: ZoneRulesException) {
                    return Result.InvalidTimezone(cmd.timezone)
                } catch (_: java.time.DateTimeException) {
                    return Result.InvalidTimezone(cmd.timezone)
                }
            } else {
                null
            }

        val before = repository.get()
        val patch =
            ClinicSettingsPatch(
                name = cmd.name?.trim()?.takeIf { it.isNotEmpty() },
                timezone = parsedZone,
                workingHoursStart = cmd.workingHoursStart,
                workingHoursEnd = cmd.workingHoursEnd,
                defaultBookingMinutes = cmd.defaultBookingMinutes,
            )

        val after =
            try {
                repository.update(patch)
            } catch (e: IllegalArgumentException) {
                return Result.InvalidInput(e.message ?: "rejected by domain validation")
            } catch (e: org.springframework.dao.DataIntegrityViolationException) {
                // CHECK on working_hours_end > working_hours_start is the safety net.
                return Result.InvalidInput(e.mostSpecificCause.message ?: "rejected by DB constraint")
            }

        auditWriter.record(
            AuditEntry(
                action = "clinic_settings.updated",
                entityType = "clinic_settings",
                actorUserId = actorUserId,
                before =
                    mapOf(
                        "name" to before.name,
                        "timezone" to before.timezone.id,
                        "workingHoursStart" to before.workingHoursStart.toString(),
                        "workingHoursEnd" to before.workingHoursEnd.toString(),
                        "defaultBookingMinutes" to before.defaultBookingMinutes,
                    ),
                after =
                    mapOf(
                        "name" to after.name,
                        "timezone" to after.timezone.id,
                        "workingHoursStart" to after.workingHoursStart.toString(),
                        "workingHoursEnd" to after.workingHoursEnd.toString(),
                        "defaultBookingMinutes" to after.defaultBookingMinutes,
                    ),
            ),
        )
        log.info("clinic_settings.updated: by={}", actorUserId)
        return Result.Success(after)
    }

    sealed interface Result {
        data class Success(
            val settings: ClinicSettings,
        ) : Result

        data class InvalidTimezone(
            val supplied: String,
        ) : Result

        data class InvalidInput(
            val reason: String,
        ) : Result
    }
}
