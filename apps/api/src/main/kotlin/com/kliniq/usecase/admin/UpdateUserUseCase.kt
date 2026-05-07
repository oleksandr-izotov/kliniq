package com.kliniq.usecase.admin

import com.kliniq.domain.user.Role
import com.kliniq.domain.user.Specialty
import com.kliniq.domain.user.User
import com.kliniq.domain.user.UserStatus
import com.kliniq.infra.audit.AuditEntry
import com.kliniq.infra.audit.AuditWriter
import com.kliniq.infra.security.SessionStore
import com.kliniq.persistence.user.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Apply a partial admin-only update to a user row: role, surgeon flag +
 * specialty, status. Two structural invariants enforced before any
 * write hits the DB:
 *
 *   - **SelfLockout** — an admin cannot demote or disable themselves.
 *     They have to ask another admin. This is defensive: a single
 *     fat-fingered "Disable" on the wrong row would otherwise drop the
 *     actor out of the system.
 *
 *   - **LastAdmin** — the operation must leave at least one ACTIVE
 *     admin standing. Counted via `countActiveAdminsExcluding(id)`,
 *     which Postgres atomicity (read inside the same transaction as
 *     the write) makes safe under concurrent demotions of two
 *     admins by two threads.
 *
 * Disabling a user also kills their open sessions — same primitive
 * password-reset uses, so a disabled user is logged out instantly
 * across all their devices.
 */
@Service
class UpdateUserUseCase(
    private val users: UserRepository,
    private val sessions: SessionStore,
    private val auditWriter: AuditWriter,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * All fields nullable mean "no change". `isSurgeon` and `specialty`
     * are validated together: setting `isSurgeon=true` requires a
     * specialty (in the patch or already on the user); setting
     * `isSurgeon=false` clears the specialty regardless of what the
     * patch says.
     */
    data class Patch(
        val role: Role? = null,
        val isSurgeon: Boolean? = null,
        val specialty: Specialty? = null,
        val status: UserStatus? = null,
    ) {
        val isNoOp: Boolean
            get() = role == null && isSurgeon == null && specialty == null && status == null
    }

    @Transactional
    @Suppress("ReturnCount", "LongMethod", "CyclomaticComplexMethod") // one branch per Result variant
    fun update(
        actorUserId: UUID,
        targetId: UUID,
        patch: Patch,
    ): Result {
        if (patch.isNoOp) {
            val current = users.findById(targetId) ?: return Result.NotFound
            return Result.Success(current)
        }

        val before = users.findById(targetId) ?: return Result.NotFound
        val newRole = patch.role ?: before.role
        val newStatus = patch.status ?: before.status

        val (newIsSurgeon, newSpecialty) =
            resolveSurgeon(patch, before)
                ?: return Result.InvalidSpecialty

        // SelfLockout: actor demoting or disabling themselves.
        val isSelf = actorUserId == targetId
        val demotingSelf = isSelf && newRole != Role.ADMIN && before.role == Role.ADMIN
        val disablingSelf = isSelf && newStatus != UserStatus.ACTIVE && before.status == UserStatus.ACTIVE
        if (demotingSelf || disablingSelf) return Result.SelfLockout

        // LastAdmin: any change that strips this user's active-admin status
        // must leave at least one other active admin standing.
        val wasActiveAdmin = before.role == Role.ADMIN && before.status == UserStatus.ACTIVE
        val willBeActiveAdmin = newRole == Role.ADMIN && newStatus == UserStatus.ACTIVE
        if (wasActiveAdmin && !willBeActiveAdmin) {
            val remaining = users.countActiveAdminsExcluding(targetId)
            if (remaining == 0) return Result.LastAdmin
        }

        val after =
            users.updateAdminFields(
                id = targetId,
                role = newRole,
                isSurgeon = newIsSurgeon,
                specialty = newSpecialty,
                status = newStatus,
            ) ?: return Result.NotFound

        // Disabling a user logs them out immediately — same primitive
        // password reset uses.
        if (before.status == UserStatus.ACTIVE && after.status == UserStatus.DISABLED) {
            sessions.invalidateAllForUser(targetId)
        }

        auditWriter.record(
            AuditEntry(
                action = "user.admin_updated",
                entityType = "user",
                entityId = targetId,
                actorUserId = actorUserId,
                before =
                    mapOf(
                        "role" to before.role.name,
                        "isSurgeon" to before.isSurgeon,
                        "specialty" to before.specialty?.name,
                        "status" to before.status.name,
                    ),
                after =
                    mapOf(
                        "role" to after.role.name,
                        "isSurgeon" to after.isSurgeon,
                        "specialty" to after.specialty?.name,
                        "status" to after.status.name,
                    ),
            ),
        )
        log.info("user.admin_updated: id={} by={}", targetId, actorUserId)
        return Result.Success(after)
    }

    /**
     * Returns the merged (isSurgeon, specialty) pair, or null when the
     * combination is invalid (true without a specialty in the patch
     * or on the existing row).
     */
    private fun resolveSurgeon(
        patch: Patch,
        before: User,
    ): Pair<Boolean, Specialty?>? {
        val newIsSurgeon = patch.isSurgeon ?: before.isSurgeon
        if (!newIsSurgeon) return false to null
        val candidate = patch.specialty ?: before.specialty
        return if (candidate != null) true to candidate else null
    }

    sealed interface Result {
        data class Success(
            val user: User,
        ) : Result

        data object NotFound : Result

        /** Surgeon flag set true without a specialty on patch or row. */
        data object InvalidSpecialty : Result

        /** Actor tried to demote/disable themselves. */
        data object SelfLockout : Result

        /** Operation would leave the system with zero active admins. */
        data object LastAdmin : Result
    }
}
