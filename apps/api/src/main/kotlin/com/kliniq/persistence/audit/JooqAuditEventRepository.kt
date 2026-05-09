package com.kliniq.persistence.audit

import com.kliniq.db.tables.records.AuditEventsRecord
import com.kliniq.db.tables.references.AUDIT_EVENTS
import com.kliniq.domain.audit.AuditEvent
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository

@Repository
class JooqAuditEventRepository(
    private val dsl: DSLContext,
) : AuditEventRepository {
    override fun list(
        filter: AuditEventFilter,
        page: Int,
        pageSize: Int,
    ): List<AuditEvent> =
        dsl
            .selectFrom(AUDIT_EVENTS)
            .where(filterConditions(filter))
            .orderBy(AUDIT_EVENTS.CREATED_AT.desc())
            .limit(pageSize)
            .offset(page * pageSize)
            .fetch { it.toDomain() }

    override fun count(filter: AuditEventFilter): Int =
        dsl
            .selectCount()
            .from(AUDIT_EVENTS)
            .where(filterConditions(filter))
            .fetchOne(0, Int::class.java) ?: 0

    private fun filterConditions(filter: AuditEventFilter): Condition {
        var cond: Condition = DSL.noCondition()
        filter.entityType?.takeIf { it.isNotBlank() }?.let {
            cond = cond.and(AUDIT_EVENTS.ENTITY_TYPE.eq(it))
        }
        filter.actorUserId?.let { cond = cond.and(AUDIT_EVENTS.ACTOR_USER_ID.eq(it)) }
        filter.action?.takeIf { it.isNotBlank() }?.let {
            cond = cond.and(AUDIT_EVENTS.ACTION.eq(it))
        }
        filter.from?.let { cond = cond.and(AUDIT_EVENTS.CREATED_AT.ge(it)) }
        filter.to?.let { cond = cond.and(AUDIT_EVENTS.CREATED_AT.lt(it)) }
        return cond
    }
}

private fun AuditEventsRecord.toDomain(): AuditEvent =
    AuditEvent(
        id = requireNotNull(id) { "audit_events.id is NOT NULL but record produced null" },
        actorUserId = actorUserId,
        action = requireNotNull(action),
        entityType = requireNotNull(entityType),
        entityId = entityId,
        before = before?.data(),
        after = after?.data(),
        metadata = metadata?.data(),
        createdAt = requireNotNull(createdAt),
    )
