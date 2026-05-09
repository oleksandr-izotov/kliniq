package com.kliniq.domain.audit

import java.time.OffsetDateTime
import java.util.UUID

/**
 * Read-side projection of one audit row. Mirrors the `audit_events`
 * schema 1:1; the JSONB columns are surfaced as raw JSON strings so the
 * read API can pretty-print them client-side without a second
 * deserialization round-trip.
 *
 * The write path lives in [com.kliniq.infra.audit.AuditWriter]; this
 * value class only flows out of the read repo and through the
 * admin/audit endpoint.
 */
data class AuditEvent(
    val id: UUID,
    val actorUserId: UUID?,
    val action: String,
    val entityType: String,
    val entityId: UUID?,
    val before: String?,
    val after: String?,
    val metadata: String?,
    val createdAt: OffsetDateTime,
)
