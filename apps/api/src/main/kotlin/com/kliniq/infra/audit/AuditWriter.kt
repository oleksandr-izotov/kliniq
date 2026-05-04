package com.kliniq.infra.audit

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.f4b6a3.uuid.UuidCreator
import com.kliniq.db.tables.references.AUDIT_EVENTS
import org.jooq.DSLContext
import org.jooq.JSONB
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Append-only audit log for every state-changing event the application
 * cares about. Writes happen through jOOQ in the same transaction as the
 * caller, so a roll-back of the underlying mutation also rolls back the
 * audit entry — there is no "we did X but didn't log it" state. The DB
 * triggers in V2__auth.sql refuse UPDATE/DELETE on this table.
 */
@Component
class AuditWriter(
    private val dsl: DSLContext,
    private val mapper: ObjectMapper,
) {
    fun record(entry: AuditEntry) {
        dsl
            .insertInto(AUDIT_EVENTS)
            .set(AUDIT_EVENTS.ID, UuidCreator.getTimeOrderedEpoch())
            .set(AUDIT_EVENTS.ACTOR_USER_ID, entry.actorUserId)
            .set(AUDIT_EVENTS.ACTION, entry.action)
            .set(AUDIT_EVENTS.ENTITY_TYPE, entry.entityType)
            .set(AUDIT_EVENTS.ENTITY_ID, entry.entityId)
            .set(AUDIT_EVENTS.BEFORE, entry.before?.toJsonb())
            .set(AUDIT_EVENTS.AFTER, entry.after?.toJsonb())
            .set(AUDIT_EVENTS.METADATA, entry.metadata?.toJsonb())
            .execute()
    }

    private fun Any.toJsonb(): JSONB = JSONB.valueOf(mapper.writeValueAsString(this))
}

/**
 * Captures one audit event before the writer turns it into a row. Keep PII
 * out of [before]/[after] — patient names, raw passwords, session secrets,
 * etc. should never reach this struct.
 */
data class AuditEntry(
    val action: String,
    val entityType: String,
    val entityId: UUID? = null,
    val actorUserId: UUID? = null,
    val before: Map<String, Any?>? = null,
    val after: Map<String, Any?>? = null,
    val metadata: Map<String, Any?>? = null,
)
