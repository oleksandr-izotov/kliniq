package com.kliniq.usecase.auth

import com.kliniq.domain.user.User
import com.kliniq.infra.audit.AuditEntry
import com.kliniq.infra.audit.AuditWriter
import com.kliniq.persistence.user.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Self-service profile updates for the authenticated user. V1 scope is a
 * single field — the display name shown in the home banner, the side
 * panels, and the "Dr Surname" attribution on bookings. Role, surgeon
 * flag, specialty, status, and email all stay admin-only (see
 * UpdateUserAdminUseCase) so an end user can't promote themselves into a
 * surgeon row that the booking flow would then offer up.
 *
 * No-op idempotency: if the candidate name equals the current one
 * (after trim) we skip the UPDATE and skip the audit row. The caller
 * still gets [Result.Success] with the existing user echoed back, so
 * the SPA's "saved" toast lights up and the form clears regardless —
 * one less branch on the frontend.
 */
@Service
class UpdateProfileUseCase(
    private val users: UserRepository,
    private val auditWriter: AuditWriter,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun updateDisplayName(
        userId: UUID,
        displayName: String,
    ): Result {
        // The DTO's @Size already bounded length; the trim guards against
        // "  Same Name  " on the wire being treated as different from
        // the stored value just because of whitespace.
        val candidate = displayName.trim()
        require(candidate.isNotEmpty() && candidate.length <= User.MAX_DISPLAY_NAME) {
            "displayName must be 1..${User.MAX_DISPLAY_NAME} characters after trim"
        }

        val before =
            users.findById(userId)
                ?: error("authenticated user vanished mid-request: $userId")

        if (before.displayName == candidate) {
            return Result.Success(before)
        }

        val after =
            users.updateDisplayName(userId, candidate)
                ?: error("displayName update returned no row for existing user: $userId")

        auditWriter.record(
            AuditEntry(
                action = "user.display_name_changed",
                entityType = "user",
                entityId = userId,
                actorUserId = userId,
                before = mapOf("displayName" to before.displayName),
                after = mapOf("displayName" to after.displayName),
            ),
        )
        log.info("user.display_name_changed: user={}", userId)
        return Result.Success(after)
    }

    sealed interface Result {
        data class Success(
            val user: User,
        ) : Result
    }
}
