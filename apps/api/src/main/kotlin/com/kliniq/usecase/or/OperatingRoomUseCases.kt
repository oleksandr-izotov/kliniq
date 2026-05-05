package com.kliniq.usecase.or

import com.github.f4b6a3.uuid.UuidCreator
import com.kliniq.domain.or.NewOperatingRoom
import com.kliniq.domain.or.OperatingRoom
import com.kliniq.domain.or.OperatingRoomPatch
import com.kliniq.infra.audit.AuditEntry
import com.kliniq.infra.audit.AuditWriter
import com.kliniq.persistence.or.OperatingRoomRepository
import org.slf4j.LoggerFactory
import org.springframework.dao.DuplicateKeyException
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Create a new operating room. The DB unique constraint on `code` is the
 * source of truth; we surface a [Result.DuplicateCode] envelope when it
 * fires so the controller can return a clean 409.
 */
@Service
class CreateOperatingRoomUseCase(
    private val operatingRooms: OperatingRoomRepository,
    private val auditWriter: AuditWriter,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    data class Command(
        val code: String,
        val name: String,
        val notes: String?,
    )

    @Suppress("ReturnCount") // each branch is one informative early exit
    fun create(
        actorUserId: UUID,
        cmd: Command,
    ): Result {
        val draft =
            try {
                NewOperatingRoom(
                    id = UuidCreator.getTimeOrderedEpoch(),
                    code = cmd.code.trim(),
                    name = cmd.name.trim(),
                    notes = cmd.notes?.trim()?.takeIf { it.isNotEmpty() },
                )
            } catch (e: IllegalArgumentException) {
                return Result.InvalidInput(e.message ?: "invalid operating room")
            }

        val created =
            try {
                operatingRooms.create(draft)
            } catch (_: DuplicateKeyException) {
                return Result.DuplicateCode
            }

        auditWriter.record(
            AuditEntry(
                action = "operating_room.created",
                entityType = "operating_room",
                entityId = created.id,
                actorUserId = actorUserId,
                after = mapOf("code" to created.code, "name" to created.name, "status" to created.status.name),
            ),
        )
        log.info("operating-room.created: id={} code={} by user={}", created.id, created.code, actorUserId)
        return Result.Success(created)
    }

    sealed interface Result {
        data class Success(
            val operatingRoom: OperatingRoom,
        ) : Result

        /** Domain `init` rejected a field (length, blank). */
        data class InvalidInput(
            val reason: String,
        ) : Result

        /** UNIQUE constraint on `code` fired. */
        data object DuplicateCode : Result
    }
}

/**
 * Apply a [OperatingRoomPatch] to an existing room. No-op patches return
 * the current row unchanged; missing rows are reported as NotFound.
 */
@Service
class UpdateOperatingRoomUseCase(
    private val operatingRooms: OperatingRoomRepository,
    private val auditWriter: AuditWriter,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Suppress("ReturnCount")
    fun update(
        actorUserId: UUID,
        id: UUID,
        patch: OperatingRoomPatch,
    ): Result {
        val before = operatingRooms.findById(id) ?: return Result.NotFound
        if (patch.isNoOp) return Result.Success(before)

        val after =
            try {
                operatingRooms.update(id, patch) ?: return Result.NotFound
            } catch (_: IllegalArgumentException) {
                return Result.InvalidInput("rejected by domain validation")
            }

        auditWriter.record(
            AuditEntry(
                action = "operating_room.updated",
                entityType = "operating_room",
                entityId = id,
                actorUserId = actorUserId,
                before =
                    mapOf(
                        "name" to before.name,
                        "status" to before.status.name,
                        "notes" to before.notes,
                    ),
                after =
                    mapOf(
                        "name" to after.name,
                        "status" to after.status.name,
                        "notes" to after.notes,
                    ),
            ),
        )
        log.info("operating-room.updated: id={} by user={}", id, actorUserId)
        return Result.Success(after)
    }

    sealed interface Result {
        data class Success(
            val operatingRoom: OperatingRoom,
        ) : Result

        data object NotFound : Result

        data class InvalidInput(
            val reason: String,
        ) : Result
    }
}

/** Read-only listing — no auditing. */
@Service
class ListOperatingRoomsUseCase(
    private val operatingRooms: OperatingRoomRepository,
) {
    fun list(includeRetired: Boolean = false): List<OperatingRoom> = operatingRooms.listAll(includeRetired)

    fun get(id: UUID): OperatingRoom? = operatingRooms.findById(id)
}
