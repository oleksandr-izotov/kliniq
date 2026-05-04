package com.kliniq.persistence.auth

import com.kliniq.db.tables.references.PASSWORD_RESET_TOKENS
import org.jooq.DSLContext
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Persistence port for [com.kliniq.usecase.auth.PasswordResetTokenService].
 * Same contract as [EmailVerificationTokenRepository] — only the SHA-256
 * hash of the token reaches the DB; the plaintext is sent in the email and
 * never logged.
 */
interface PasswordResetTokenRepository {
    fun insert(
        tokenHash: ByteArray,
        userId: UUID,
        expiresAt: OffsetDateTime,
    )

    fun findActiveUserIdByHash(
        tokenHash: ByteArray,
        now: OffsetDateTime,
    ): UUID?

    fun markConsumed(
        tokenHash: ByteArray,
        consumedAt: OffsetDateTime,
    ): Boolean

    /** Removes any pending tokens for the user — used after a successful reset. */
    fun deleteByUserId(userId: UUID): Int
}

@Repository
class JooqPasswordResetTokenRepository(
    private val dsl: DSLContext,
) : PasswordResetTokenRepository {
    override fun insert(
        tokenHash: ByteArray,
        userId: UUID,
        expiresAt: OffsetDateTime,
    ) {
        dsl
            .insertInto(PASSWORD_RESET_TOKENS)
            .set(PASSWORD_RESET_TOKENS.TOKEN_HASH, tokenHash)
            .set(PASSWORD_RESET_TOKENS.USER_ID, userId)
            .set(PASSWORD_RESET_TOKENS.EXPIRES_AT, expiresAt)
            .execute()
    }

    override fun findActiveUserIdByHash(
        tokenHash: ByteArray,
        now: OffsetDateTime,
    ): UUID? =
        dsl
            .select(PASSWORD_RESET_TOKENS.USER_ID)
            .from(PASSWORD_RESET_TOKENS)
            .where(PASSWORD_RESET_TOKENS.TOKEN_HASH.eq(tokenHash))
            .and(PASSWORD_RESET_TOKENS.CONSUMED_AT.isNull)
            .and(PASSWORD_RESET_TOKENS.EXPIRES_AT.gt(now))
            .fetchOne()
            ?.value1()

    override fun markConsumed(
        tokenHash: ByteArray,
        consumedAt: OffsetDateTime,
    ): Boolean {
        val updated =
            dsl
                .update(PASSWORD_RESET_TOKENS)
                .set(PASSWORD_RESET_TOKENS.CONSUMED_AT, consumedAt)
                .where(PASSWORD_RESET_TOKENS.TOKEN_HASH.eq(tokenHash))
                .and(PASSWORD_RESET_TOKENS.CONSUMED_AT.isNull)
                .execute()
        return updated == 1
    }

    override fun deleteByUserId(userId: UUID): Int =
        dsl
            .deleteFrom(PASSWORD_RESET_TOKENS)
            .where(PASSWORD_RESET_TOKENS.USER_ID.eq(userId))
            .execute()
}
