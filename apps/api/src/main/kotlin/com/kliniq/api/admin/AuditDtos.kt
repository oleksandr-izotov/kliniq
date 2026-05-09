package com.kliniq.api.admin

import com.fasterxml.jackson.annotation.JsonRawValue
import com.kliniq.domain.audit.AuditEvent
import jakarta.validation.constraints.Min
import org.springframework.format.annotation.DateTimeFormat
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Wire shape for one audit row. The three JSON columns are emitted as
 * raw JSON via [JsonRawValue] — the SPA already needs to render them
 * pretty-printed, so re-serializing to a String just to deserialize on
 * the other side would be wasted work. PII discipline holds because
 * the writers (CreateBookingUseCase et al.) never put `patient_ref`
 * into these fields in the first place.
 */
data class AuditEventDto(
    val id: UUID,
    val actorUserId: UUID?,
    val action: String,
    val entityType: String,
    val entityId: UUID?,
    @get:JsonRawValue val before: String?,
    @get:JsonRawValue val after: String?,
    @get:JsonRawValue val metadata: String?,
    val createdAt: OffsetDateTime,
) {
    companion object {
        fun of(e: AuditEvent): AuditEventDto =
            AuditEventDto(
                id = e.id,
                actorUserId = e.actorUserId,
                action = e.action,
                entityType = e.entityType,
                entityId = e.entityId,
                before = e.before,
                after = e.after,
                metadata = e.metadata,
                createdAt = e.createdAt,
            )
    }
}

data class AuditEventPageDto(
    val items: List<AuditEventDto>,
    val page: Int,
    val pageSize: Int,
    val total: Int,
)

/** Bean-validated query parameters for the audit listing. */
data class AuditEventListQuery(
    val entityType: String? = null,
    val actorUserId: UUID? = null,
    val action: String? = null,
    @field:DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) val from: OffsetDateTime? = null,
    @field:DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) val to: OffsetDateTime? = null,
    @field:Min(0) val page: Int = 0,
    @field:Min(1) val pageSize: Int = 50,
)
