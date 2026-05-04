package com.kliniq.persistence.auth

import com.kliniq.db.tables.references.EMAIL_VERIFICATION_TOKENS
import org.jooq.DSLContext
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Persistence port for [com.kliniq.usecase.auth.EmailVerificationTokenService].
 * The token's plaintext form never enters the DB — only its SHA-256 digest is
 * stored. Implementations must treat tokens as opaque bytes and never log them.
 */
interface EmailVerificationTokenRepository {
    fun insert(
        tokenHash: ByteArray,
        userId: UUID,
        expiresAt: OffsetDateTime,
    )

    /** Returns the user id only if the token exists, hasn't been consumed, and isn't expired. */
    fun findActiveUserIdByHash(
        tokenHash: ByteArray,
        now: OffsetDateTime,
    ): UUID?

    /** Marks the token as consumed atomically. Returns true if a row was updated. */
    fun markConsumed(
        tokenHash: ByteArray,
        consumedAt: OffsetDateTime,
    ): Boolean

    /** Used during password reset re-issuance to invalidate any pending tokens for a user. */
    fun deleteByUserId(userId: UUID): Int
}

@Repository
class JooqEmailVerificationTokenRepository(
    private val dsl: DSLContext,
) : EmailVerificationTokenRepository {
    override fun insert(
        tokenHash: ByteArray,
        userId: UUID,
        expiresAt: OffsetDateTime,
    ) {
        dsl
            .insertInto(EMAIL_VERIFICATION_TOKENS)
            .set(EMAIL_VERIFICATION_TOKENS.TOKEN_HASH, tokenHash)
            .set(EMAIL_VERIFICATION_TOKENS.USER_ID, userId)
            .set(EMAIL_VERIFICATION_TOKENS.EXPIRES_AT, expiresAt)
            .execute()
    }

    override fun findActiveUserIdByHash(
        tokenHash: ByteArray,
        now: OffsetDateTime,
    ): UUID? =
        dsl
            .select(EMAIL_VERIFICATION_TOKENS.USER_ID)
            .from(EMAIL_VERIFICATION_TOKENS)
            .where(EMAIL_VERIFICATION_TOKENS.TOKEN_HASH.eq(tokenHash))
            .and(EMAIL_VERIFICATION_TOKENS.CONSUMED_AT.isNull)
            .and(EMAIL_VERIFICATION_TOKENS.EXPIRES_AT.gt(now))
            .fetchOne()
            ?.value1()

    override fun markConsumed(
        tokenHash: ByteArray,
        consumedAt: OffsetDateTime,
    ): Boolean {
        val updated =
            dsl
                .update(EMAIL_VERIFICATION_TOKENS)
                .set(EMAIL_VERIFICATION_TOKENS.CONSUMED_AT, consumedAt)
                .where(EMAIL_VERIFICATION_TOKENS.TOKEN_HASH.eq(tokenHash))
                .and(EMAIL_VERIFICATION_TOKENS.CONSUMED_AT.isNull)
                .execute()
        return updated == 1
    }

    override fun deleteByUserId(userId: UUID): Int =
        dsl
            .deleteFrom(EMAIL_VERIFICATION_TOKENS)
            .where(EMAIL_VERIFICATION_TOKENS.USER_ID.eq(userId))
            .execute()
}
