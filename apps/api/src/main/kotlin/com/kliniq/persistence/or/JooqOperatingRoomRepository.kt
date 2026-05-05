package com.kliniq.persistence.or

import com.kliniq.db.tables.records.OperatingRoomsRecord
import com.kliniq.db.tables.references.OPERATING_ROOMS
import com.kliniq.domain.or.NewOperatingRoom
import com.kliniq.domain.or.OperatingRoom
import com.kliniq.domain.or.OperatingRoomPatch
import com.kliniq.domain.or.OperatingRoomStatus
import org.jooq.DSLContext
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Repository
class JooqOperatingRoomRepository(
    private val dsl: DSLContext,
) : OperatingRoomRepository {
    override fun create(newOperatingRoom: NewOperatingRoom): OperatingRoom {
        val record =
            dsl
                .insertInto(OPERATING_ROOMS)
                .set(OPERATING_ROOMS.ID, newOperatingRoom.id)
                .set(OPERATING_ROOMS.CODE, newOperatingRoom.code)
                .set(OPERATING_ROOMS.NAME, newOperatingRoom.name)
                .set(OPERATING_ROOMS.NOTES, newOperatingRoom.notes?.takeIf { it.isNotBlank() })
                // status defaults ACTIVE; created_at / updated_at default at the DB layer
                .returning()
                .fetchOne() ?: error("INSERT into operating_rooms returned no row for id=${newOperatingRoom.id}")
        return record.toDomain()
    }

    override fun findById(id: UUID): OperatingRoom? =
        dsl
            .selectFrom(OPERATING_ROOMS)
            .where(OPERATING_ROOMS.ID.eq(id))
            .fetchOne()
            ?.toDomain()

    override fun findByCode(code: String): OperatingRoom? =
        dsl
            .selectFrom(OPERATING_ROOMS)
            .where(OPERATING_ROOMS.CODE.eq(code))
            .fetchOne()
            ?.toDomain()

    override fun listAll(includeRetired: Boolean): List<OperatingRoom> {
        val query = dsl.selectFrom(OPERATING_ROOMS)
        val filtered =
            if (includeRetired) {
                query
            } else {
                query.where(OPERATING_ROOMS.STATUS.ne(OperatingRoomStatus.RETIRED.name))
            }
        return filtered.orderBy(OPERATING_ROOMS.CODE.asc()).fetch().map { it.toDomain() }
    }

    /**
     * Read-modify-write inside a transaction: load the current row, fold the
     * patch over it, then write back the merged values. Concurrent updates
     * lose the last-write-wins race, which is acceptable for OR management
     * — the real cross-row invariant (no overlapping bookings) lives on the
     * `bookings` table.
     */
    @Transactional
    @Suppress("ReturnCount") // each branch is one informative early exit
    override fun update(
        id: UUID,
        patch: OperatingRoomPatch,
    ): OperatingRoom? {
        val current = findById(id) ?: return null
        if (patch.isNoOp) return current

        val newName = patch.name ?: current.name
        val newStatus = patch.status ?: current.status
        // Treat empty string as "clear notes" so PATCH can null out the field.
        val newNotes =
            when {
                patch.notes == null -> current.notes
                patch.notes.isBlank() -> null
                else -> patch.notes
            }

        val updated =
            dsl
                .update(OPERATING_ROOMS)
                .set(OPERATING_ROOMS.NAME, newName)
                .set(OPERATING_ROOMS.NOTES, newNotes)
                .set(OPERATING_ROOMS.STATUS, newStatus.name)
                .where(OPERATING_ROOMS.ID.eq(id))
                .execute()
        return if (updated == 1) findById(id) else null
    }

    override fun archive(id: UUID): Boolean {
        val updated =
            dsl
                .update(OPERATING_ROOMS)
                .set(OPERATING_ROOMS.STATUS, OperatingRoomStatus.RETIRED.name)
                .where(OPERATING_ROOMS.ID.eq(id))
                // Idempotent — re-archiving an already-RETIRED room is a no-op
                // we don't want to count as "actually changed".
                .and(OPERATING_ROOMS.STATUS.ne(OperatingRoomStatus.RETIRED.name))
                .execute()
        return updated == 1
    }
}

private fun OperatingRoomsRecord.toDomain(): OperatingRoom =
    OperatingRoom(
        id = requireNotNull(id) { "operating_rooms.id is NOT NULL but record produced null" },
        code = requireNotNull(code),
        name = requireNotNull(name),
        status = OperatingRoomStatus.valueOf(requireNotNull(status)),
        notes = notes,
        createdAt = requireNotNull(createdAt),
        updatedAt = requireNotNull(updatedAt),
    )
