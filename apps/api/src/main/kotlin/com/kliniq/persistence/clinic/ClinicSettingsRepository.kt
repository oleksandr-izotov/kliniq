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
        return ClinicSettings(
            name = requireNotNull(record.name),
            timezone = ZoneId.of(requireNotNull(record.timezone)),
            workingHoursStart = requireNotNull(record.workingHoursStart),
            workingHoursEnd = requireNotNull(record.workingHoursEnd),
            defaultBookingMinutes = requireNotNull(record.defaultBookingMinutes),
            updatedAt = requireNotNull(record.updatedAt),
        )
    }
}
