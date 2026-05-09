package com.kliniq.persistence.user

import com.kliniq.db.tables.records.UsersRecord
import com.kliniq.db.tables.references.USERS
import com.kliniq.domain.user.NewUser
import com.kliniq.domain.user.Role
import com.kliniq.domain.user.Specialty
import com.kliniq.domain.user.User
import com.kliniq.domain.user.UserStatus
import org.jooq.DSLContext
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Single port over the users table; splitting the impl just to dodge
 * detekt's function-count threshold would scatter related queries
 * across files for no design win.
 */
@Repository
@Suppress("TooManyFunctions")
class JooqUserRepository(
    private val dsl: DSLContext,
) : UserRepository {
    override fun create(newUser: NewUser): User {
        val record =
            dsl
                .insertInto(USERS)
                .set(USERS.ID, newUser.id)
                .set(USERS.EMAIL, newUser.email)
                .set(USERS.PASSWORD_HASH, newUser.passwordHash)
                .set(USERS.DISPLAY_NAME, newUser.displayName)
                .set(USERS.ROLE, newUser.role.name)
                .set(USERS.IS_SURGEON, newUser.isSurgeon)
                .set(USERS.SPECIALTY, newUser.specialty?.name)
                // status, created_at, updated_at default at the DB level
                .returning()
                .fetchOne() ?: error("INSERT into users returned no row for id=${newUser.id}")
        return record.toDomain()
    }

    override fun findById(id: UUID): User? =
        dsl
            .selectFrom(USERS)
            .where(USERS.ID.eq(id))
            .fetchOne()
            ?.toDomain()

    override fun findByEmail(email: String): User? =
        dsl
            .selectFrom(USERS)
            .where(USERS.EMAIL_NORMALIZED.eq(email.lowercase()))
            .fetchOne()
            ?.toDomain()

    override fun existsByEmail(email: String): Boolean =
        dsl.fetchExists(
            dsl
                .selectOne()
                .from(USERS)
                .where(USERS.EMAIL_NORMALIZED.eq(email.lowercase())),
        )

    override fun findActiveSurgeons(): List<User> =
        dsl
            .selectFrom(USERS)
            .where(USERS.IS_SURGEON.isTrue)
            .and(USERS.STATUS.eq(UserStatus.ACTIVE.name))
            .orderBy(USERS.DISPLAY_NAME.asc())
            .fetch { it.toDomain() }

    override fun markEmailVerified(
        id: UUID,
        verifiedAt: OffsetDateTime,
    ): Boolean {
        val updated =
            dsl
                .update(USERS)
                .set(USERS.EMAIL_VERIFIED_AT, verifiedAt)
                .where(USERS.ID.eq(id))
                .and(USERS.EMAIL_VERIFIED_AT.isNull)
                .execute()
        return updated == 1
    }

    override fun updatePasswordHash(
        id: UUID,
        passwordHash: String,
    ): Boolean {
        val updated =
            dsl
                .update(USERS)
                .set(USERS.PASSWORD_HASH, passwordHash)
                .where(USERS.ID.eq(id))
                .execute()
        return updated == 1
    }

    override fun updateDisplayName(
        id: UUID,
        displayName: String,
    ): User? =
        dsl
            .update(USERS)
            .set(USERS.DISPLAY_NAME, displayName)
            .where(USERS.ID.eq(id))
            .returning()
            .fetchOne()
            ?.toDomain()

    override fun listAdminUsers(
        filter: UserListFilter,
        page: Int,
        pageSize: Int,
    ): List<User> =
        dsl
            .selectFrom(USERS)
            .where(filterConditions(filter))
            .orderBy(USERS.DISPLAY_NAME.asc(), USERS.EMAIL.asc())
            .limit(pageSize)
            .offset(page * pageSize)
            .fetch { it.toDomain() }

    override fun countAdminUsers(filter: UserListFilter): Int =
        dsl
            .selectCount()
            .from(USERS)
            .where(filterConditions(filter))
            .fetchOne(0, Int::class.java) ?: 0

    override fun countActiveAdminsExcluding(excludingId: UUID?): Int {
        val base =
            dsl
                .selectCount()
                .from(USERS)
                .where(USERS.ROLE.eq(Role.ADMIN.name))
                .and(USERS.STATUS.eq(UserStatus.ACTIVE.name))
        val q = if (excludingId != null) base.and(USERS.ID.ne(excludingId)) else base
        return q.fetchOne(0, Int::class.java) ?: 0
    }

    override fun updateAdminFields(
        id: UUID,
        role: Role,
        isSurgeon: Boolean,
        specialty: Specialty?,
        status: UserStatus,
    ): User? =
        dsl
            .update(USERS)
            .set(USERS.ROLE, role.name)
            .set(USERS.IS_SURGEON, isSurgeon)
            .set(USERS.SPECIALTY, specialty?.name)
            .set(USERS.STATUS, status.name)
            .where(USERS.ID.eq(id))
            .returning()
            .fetchOne()
            ?.toDomain()

    private fun filterConditions(filter: UserListFilter): org.jooq.Condition {
        var cond: org.jooq.Condition =
            org.jooq.impl.DSL
                .noCondition()
        filter.q?.takeIf { it.isNotBlank() }?.let { q ->
            val like = "%${q.lowercase()}%"
            cond =
                cond.and(
                    USERS.DISPLAY_NAME
                        .lower()
                        .like(like)
                        .or(USERS.EMAIL_NORMALIZED.like(like)),
                )
        }
        filter.role?.let { cond = cond.and(USERS.ROLE.eq(it.name)) }
        filter.isSurgeon?.let { cond = cond.and(USERS.IS_SURGEON.eq(it)) }
        filter.status?.let { cond = cond.and(USERS.STATUS.eq(it.name)) }
        return cond
    }
}

private fun UsersRecord.toDomain(): User =
    User(
        id = requireNotNull(id) { "users.id is NOT NULL but record produced null" },
        email = requireNotNull(email),
        emailVerifiedAt = emailVerifiedAt,
        passwordHash = passwordHash,
        displayName = requireNotNull(displayName),
        role = Role.valueOf(requireNotNull(role)),
        isSurgeon = requireNotNull(isSurgeon),
        specialty = specialty?.let(Specialty::valueOf),
        status = UserStatus.valueOf(requireNotNull(status)),
        createdAt = requireNotNull(createdAt),
        updatedAt = requireNotNull(updatedAt),
    )
