package com.kliniq.persistence.audit

import com.kliniq.domain.audit.AuditEvent
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Read-side port over `audit_events`. The DB triggers in V2 forbid
 * UPDATE/DELETE on this table — the only way audit rows reach
 * persistence is through [com.kliniq.infra.audit.AuditWriter].
 */
interface AuditEventRepository {
    fun list(
        filter: AuditEventFilter,
        page: Int,
        pageSize: Int,
    ): List<AuditEvent>

    fun count(filter: AuditEventFilter): Int
}

/** Optional filters for the admin audit-log listing — all null = no constraint. */
data class AuditEventFilter(
    val entityType: String? = null,
    val actorUserId: UUID? = null,
    val action: String? = null,
    /** Inclusive lower bound on `created_at`. */
    val from: OffsetDateTime? = null,
    /** Exclusive upper bound on `created_at`. */
    val to: OffsetDateTime? = null,
)
