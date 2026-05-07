package com.kliniq.usecase.invitation

import com.github.f4b6a3.uuid.UuidCreator
import com.kliniq.domain.auth.Session
import com.kliniq.domain.user.NewUser
import com.kliniq.domain.user.User
import com.kliniq.infra.audit.AuditEntry
import com.kliniq.infra.audit.AuditWriter
import com.kliniq.infra.security.PasswordHasher
import com.kliniq.infra.security.SessionStore
import com.kliniq.infra.security.breach.BreachedPasswordChecker
import com.kliniq.persistence.invitation.InvitationRepository
import com.kliniq.persistence.user.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

/**
 * Public flow: invitee clicks the email link, picks a password and
 * display name, posts here. We hash the token, find the matching
 * invitation, verify it's still active, create a fresh user with the
 * pre-set role / surgeon flag, mark the invitation accepted, and
 * return a session — all inside one transaction so a partial failure
 * never leaves us with a created user and an unaccepted invitation
 * (or vice versa).
 *
 * The created user has `email_verified_at` already stamped — clicking
 * a token that was emailed to that address proves control of the
 * inbox, so the verify-email round-trip is redundant.
 */
@Service
@Suppress("LongParameterList") // orchestration use case wires many collaborators
class AcceptInvitationUseCase(
    private val invitations: InvitationRepository,
    private val users: UserRepository,
    private val tokens: InvitationTokenService,
    private val passwordHasher: PasswordHasher,
    private val breachChecker: BreachedPasswordChecker,
    private val sessions: SessionStore,
    private val auditWriter: AuditWriter,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    data class Command(
        val token: String,
        val password: String,
        val displayName: String,
    )

    @Transactional
    @Suppress("ReturnCount", "LongMethod") // ceremony of validation guards + dual audit
    fun accept(cmd: Command): Result {
        if (cmd.password.length < MIN_PASSWORD) return Result.InvalidInput("password too short")
        if (cmd.displayName.isBlank()) return Result.InvalidInput("displayName required")

        val tokenHash = tokens.hash(cmd.token)
        val invitation = invitations.findByTokenHash(tokenHash) ?: return Result.InvalidToken

        val now = clock.instant().atZone(clock.zone).toOffsetDateTime()
        if (invitation.revokedAt != null) return Result.Revoked
        if (invitation.acceptedAt != null) return Result.AlreadyAccepted
        if (!now.isBefore(invitation.expiresAt)) return Result.Expired

        // Last guard before we hash the password — between the time the
        // invite was issued and now, someone may have registered the same
        // email through the public /register route.
        if (users.existsByEmail(invitation.email)) return Result.UserAlreadyExists

        if (breachChecker.isBreached(cmd.password)) return Result.PasswordBreached

        val userId: UUID = UuidCreator.getTimeOrderedEpoch()
        val user =
            users.create(
                NewUser(
                    id = userId,
                    email = invitation.email,
                    passwordHash = passwordHasher.hash(cmd.password),
                    displayName = cmd.displayName.trim(),
                    role = invitation.role,
                    isSurgeon = invitation.isSurgeon,
                    specialty = invitation.specialty,
                ),
            )
        // Stamp verified_at — clicking the token proved inbox control.
        users.markEmailVerified(user.id, now)

        val accepted = invitations.markAccepted(invitation.id, now)
        if (!accepted) {
            // Lost a race against another accept / revoke. Roll the
            // transaction back so we don't leave an orphan user.
            error("invitation ${invitation.id} race-lost on markAccepted; transaction will roll back")
        }

        val session = sessions.create(user.id)

        auditWriter.record(
            AuditEntry(
                action = "invitation.accepted",
                entityType = "invitation",
                entityId = invitation.id,
                actorUserId = user.id,
                metadata = mapOf("userId" to user.id.toString(), "email" to user.email),
            ),
        )
        auditWriter.record(
            AuditEntry(
                action = "user.invited",
                entityType = "user",
                entityId = user.id,
                actorUserId = invitation.issuedById,
                after =
                    mapOf(
                        "email" to user.email,
                        "displayName" to user.displayName,
                        "role" to user.role.name,
                        "isSurgeon" to user.isSurgeon,
                        "specialty" to user.specialty?.name,
                    ),
            ),
        )
        log.info(
            "invitation.accepted: id={} → user {} ({}), session {}",
            invitation.id,
            user.id,
            user.role,
            session.id.take(SESSION_LOG_PREFIX),
        )
        // Re-read so the response reflects email_verified_at.
        val verified = users.findById(user.id) ?: user
        return Result.Success(verified, session)
    }

    sealed interface Result {
        data class Success(
            val user: User,
            val session: Session,
        ) : Result

        data class InvalidInput(
            val reason: String,
        ) : Result

        /** Token doesn't match any invitation. Don't tell callers more than this. */
        data object InvalidToken : Result

        data object Expired : Result

        data object Revoked : Result

        data object AlreadyAccepted : Result

        /** Email was registered via the public /register route after the invite was issued. */
        data object UserAlreadyExists : Result

        data object PasswordBreached : Result
    }

    companion object {
        private const val MIN_PASSWORD = 12
        private const val SESSION_LOG_PREFIX = 6
    }
}
