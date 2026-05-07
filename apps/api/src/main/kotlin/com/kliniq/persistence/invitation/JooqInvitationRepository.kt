package com.kliniq.persistence.invitation

import com.kliniq.db.tables.records.UserInvitationsRecord
import com.kliniq.db.tables.references.USER_INVITATIONS
import com.kliniq.domain.invitation.Invitation
import com.kliniq.domain.invitation.NewInvitation
import com.kliniq.domain.user.Role
import com.kliniq.domain.user.Specialty
import org.jooq.DSLContext
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.util.UUID

@Repository
class JooqInvitationRepository(
    private val dsl: DSLContext,
) : InvitationRepository {
    override fun create(invite: NewInvitation): Invitation {
        val record =
            dsl
                .insertInto(USER_INVITATIONS)
                .set(USER_INVITATIONS.ID, invite.id)
                .set(USER_INVITATIONS.EMAIL, invite.email)
                .set(USER_INVITATIONS.ROLE, invite.role.name)
                .set(USER_INVITATIONS.IS_SURGEON, invite.isSurgeon)
                .set(USER_INVITATIONS.SPECIALTY, invite.specialty?.name)
                .set(USER_INVITATIONS.TOKEN_HASH, invite.tokenHash)
                .set(USER_INVITATIONS.ISSUED_BY_ID, invite.issuedById)
                .set(USER_INVITATIONS.EXPIRES_AT, invite.expiresAt)
                // issued_at, accepted_at, revoked_at default at the DB layer
                .returning()
                .fetchOne() ?: error("INSERT into user_invitations returned no row for id=${invite.id}")
        return record.toDomain()
    }

    override fun findById(id: UUID): Invitation? =
        dsl
            .selectFrom(USER_INVITATIONS)
            .where(USER_INVITATIONS.ID.eq(id))
            .fetchOne()
            ?.toDomain()

    override fun findByTokenHash(tokenHash: String): Invitation? =
        dsl
            .selectFrom(USER_INVITATIONS)
            .where(USER_INVITATIONS.TOKEN_HASH.eq(tokenHash))
            .fetchOne()
            ?.toDomain()

    override fun findPendingByEmail(email: String): Invitation? =
        dsl
            .selectFrom(USER_INVITATIONS)
            .where(USER_INVITATIONS.EMAIL_NORMALIZED.eq(email.lowercase()))
            .and(USER_INVITATIONS.ACCEPTED_AT.isNull)
            .and(USER_INVITATIONS.REVOKED_AT.isNull)
            .fetchOne()
            ?.toDomain()

    override fun listAll(includeHistory: Boolean): List<Invitation> {
        val query = dsl.selectFrom(USER_INVITATIONS)
        val filtered =
            if (includeHistory) {
                query
            } else {
                query
                    .where(USER_INVITATIONS.ACCEPTED_AT.isNull)
                    .and(USER_INVITATIONS.REVOKED_AT.isNull)
            }
        return filtered
            .orderBy(USER_INVITATIONS.ISSUED_AT.desc())
            .fetch { it.toDomain() }
    }

    override fun markAccepted(
        id: UUID,
        acceptedAt: OffsetDateTime,
    ): Boolean =
        dsl
            .update(USER_INVITATIONS)
            .set(USER_INVITATIONS.ACCEPTED_AT, acceptedAt)
            .where(USER_INVITATIONS.ID.eq(id))
            .and(USER_INVITATIONS.ACCEPTED_AT.isNull)
            .and(USER_INVITATIONS.REVOKED_AT.isNull)
            .execute() == 1

    override fun markRevoked(
        id: UUID,
        revokedAt: OffsetDateTime,
    ): Boolean =
        dsl
            .update(USER_INVITATIONS)
            .set(USER_INVITATIONS.REVOKED_AT, revokedAt)
            .where(USER_INVITATIONS.ID.eq(id))
            .and(USER_INVITATIONS.ACCEPTED_AT.isNull)
            .and(USER_INVITATIONS.REVOKED_AT.isNull)
            .execute() == 1
}

private fun UserInvitationsRecord.toDomain(): Invitation =
    Invitation(
        id = requireNotNull(id) { "user_invitations.id is NOT NULL but record produced null" },
        email = requireNotNull(email),
        role = Role.valueOf(requireNotNull(role)),
        isSurgeon = requireNotNull(isSurgeon),
        specialty = specialty?.let(Specialty::valueOf),
        tokenHash = requireNotNull(tokenHash),
        issuedById = requireNotNull(issuedById),
        issuedAt = requireNotNull(issuedAt),
        expiresAt = requireNotNull(expiresAt),
        acceptedAt = acceptedAt,
        revokedAt = revokedAt,
    )
