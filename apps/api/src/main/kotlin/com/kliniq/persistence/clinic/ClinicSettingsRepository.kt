package com.kliniq.persistence.clinic

import com.kliniq.db.tables.references.CLINIC_SETTINGS
import com.kliniq.domain.clinic.ClinicSettings
import org.jooq.DSLContext
import org.springframework.stereotype.Repository
import java.time.ZoneId

/**
 * Persistence-layer port for the singleton [ClinicSettings] row.
 * Day 22 will extend this interface with `update(...)`.
 */
interface ClinicSettingsRepository {
    /**
     * Returns the seeded singleton row. The V3 migration inserts it, so
     * this never returns null in a properly migrated database — implementations
     * raise [IllegalStateException] if the row is missing rather than expose
     * a nullable type to every caller.
     */
    fun get(): ClinicSettings

    /**
     * Apply [patch] to the singleton row. Returns the refreshed [ClinicSettings].
     * No-op patches return the row unchanged.
     */
    fun update(patch: ClinicSettingsPatch): ClinicSettings
}

/**
 * Partial update for the singleton row. `null` means "leave it alone".
 */
data class ClinicSettingsPatch(
    val name: String? = null,
    val timezone: ZoneId? = null,
    val workingHoursStart: java.time.LocalTime? = null,
    val workingHoursEnd: java.time.LocalTime? = null,
    val defaultBookingMinutes: Int? = null,
) {
    val isNoOp: Boolean
        get() =
            name == null &&
                timezone == null &&
                workingHoursStart == null &&
                workingHoursEnd == null &&
                defaultBookingMinutes == null
}

@Repository
class JooqClinicSettingsRepository(
    private val dsl: DSLContext,
) : ClinicSettingsRepository {
    override fun get(): ClinicSettings {
        val record =
            dsl
                .selectFrom(CLINIC_SETTINGS)
                .where(CLINIC_SETTINGS.ID.eq(1))
                .fetchOne() ?: error("clinic_settings.id=1 row missing — V3 migration not applied?")
        return record.toDomain()
    }

    /**
     * Read-modify-write inside a transaction. Same race story as the OR
     * patch: concurrent admin edits last-write-wins. We don't expect this
     * to be a hot path.
     */
    @org.springframework.transaction.annotation.Transactional
    override fun update(patch: ClinicSettingsPatch): ClinicSettings {
        if (patch.isNoOp) return get()
        val current = get()
        val newName = patch.name ?: current.name
        val newTz = patch.timezone ?: current.timezone
        val newStart = patch.workingHoursStart ?: current.workingHoursStart
        val newEnd = patch.workingHoursEnd ?: current.workingHoursEnd
        val newDefault = patch.defaultBookingMinutes ?: current.defaultBookingMinutes
        // The DB CHECK on (working_hours_end > working_hours_start) is the
        // ultimate enforcement; the use-case layer pre-checks for a clean
        // 400 envelope, but a manual write here would otherwise surface as
        // a generic DataIntegrityViolation.
        require(newEnd.isAfter(newStart)) {
            "workingHoursEnd must be strictly after workingHoursStart"
        }
        dsl
            .update(CLINIC_SETTINGS)
            .set(CLINIC_SETTINGS.NAME, newName)
            .set(CLINIC_SETTINGS.TIMEZONE, newTz.id)
            .set(CLINIC_SETTINGS.WORKING_HOURS_START, newStart)
            .set(CLINIC_SETTINGS.WORKING_HOURS_END, newEnd)
            .set(CLINIC_SETTINGS.DEFAULT_BOOKING_MINUTES, newDefault)
            .where(CLINIC_SETTINGS.ID.eq(1))
            .execute()
        return get()
    }

    private fun com.kliniq.db.tables.records.ClinicSettingsRecord.toDomain(): ClinicSettings =
        ClinicSettings(
            name = requireNotNull(name),
            timezone = ZoneId.of(requireNotNull(timezone)),
            workingHoursStart = requireNotNull(workingHoursStart),
            workingHoursEnd = requireNotNull(workingHoursEnd),
            defaultBookingMinutes = requireNotNull(defaultBookingMinutes),
            updatedAt = requireNotNull(updatedAt),
        )
}
