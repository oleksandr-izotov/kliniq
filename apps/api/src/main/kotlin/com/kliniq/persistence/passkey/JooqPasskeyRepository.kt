package com.kliniq.persistence.passkey

import com.kliniq.db.tables.records.PasskeysRecord
import com.kliniq.db.tables.references.PASSKEYS
import com.kliniq.domain.passkey.NewPasskey
import com.kliniq.domain.passkey.Passkey
import org.jooq.DSLContext
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.util.UUID

@Repository
class JooqPasskeyRepository(
    private val dsl: DSLContext,
) : PasskeyRepository {
    override fun create(newPasskey: NewPasskey): Passkey {
        val record =
            dsl
                .insertInto(PASSKEYS)
                .set(PASSKEYS.ID, newPasskey.id)
                .set(PASSKEYS.USER_ID, newPasskey.userId)
                .set(PASSKEYS.CREDENTIAL_ID, newPasskey.credentialId)
                .set(PASSKEYS.PUBLIC_KEY, newPasskey.publicKey)
                .set(PASSKEYS.SIGNATURE_COUNTER, newPasskey.signatureCounter)
                .set(PASSKEYS.AAGUID, newPasskey.aaguid)
                .set(PASSKEYS.DEVICE_NAME, newPasskey.deviceName)
                // created_at, last_used_at default at the DB layer
                .returning()
                .fetchOne() ?: error("INSERT into passkeys returned no row for id=${newPasskey.id}")
        return record.toDomain()
    }

    override fun findByCredentialId(credentialId: ByteArray): Passkey? =
        dsl
            .selectFrom(PASSKEYS)
            .where(PASSKEYS.CREDENTIAL_ID.eq(credentialId))
            .fetchOne()
            ?.toDomain()

    override fun findByIdAndUserId(
        id: UUID,
        userId: UUID,
    ): Passkey? =
        dsl
            .selectFrom(PASSKEYS)
            .where(PASSKEYS.ID.eq(id))
            .and(PASSKEYS.USER_ID.eq(userId))
            .fetchOne()
            ?.toDomain()

    override fun findByUserId(userId: UUID): List<Passkey> =
        dsl
            .selectFrom(PASSKEYS)
            .where(PASSKEYS.USER_ID.eq(userId))
            .orderBy(PASSKEYS.CREATED_AT.desc())
            .fetch()
            .map { it.toDomain() }

    override fun updateAfterUse(
        id: UUID,
        signatureCounter: Long,
        lastUsedAt: OffsetDateTime,
    ): Boolean {
        val updated =
            dsl
                .update(PASSKEYS)
                .set(PASSKEYS.SIGNATURE_COUNTER, signatureCounter)
                .set(PASSKEYS.LAST_USED_AT, lastUsedAt)
                .where(PASSKEYS.ID.eq(id))
                .execute()
        return updated == 1
    }

    override fun rename(
        id: UUID,
        userId: UUID,
        newDeviceName: String,
    ): Boolean {
        val updated =
            dsl
                .update(PASSKEYS)
                .set(PASSKEYS.DEVICE_NAME, newDeviceName)
                .where(PASSKEYS.ID.eq(id))
                .and(PASSKEYS.USER_ID.eq(userId))
                .execute()
        return updated == 1
    }

    override fun delete(
        id: UUID,
        userId: UUID,
    ): Boolean {
        val deleted =
            dsl
                .deleteFrom(PASSKEYS)
                .where(PASSKEYS.ID.eq(id))
                .and(PASSKEYS.USER_ID.eq(userId))
                .execute()
        return deleted == 1
    }
}

private fun PasskeysRecord.toDomain(): Passkey =
    Passkey(
        id = requireNotNull(id) { "passkeys.id is NOT NULL but record produced null" },
        userId = requireNotNull(userId),
        credentialId = requireNotNull(credentialId),
        publicKey = requireNotNull(publicKey),
        signatureCounter = requireNotNull(signatureCounter),
        aaguid = aaguid,
        deviceName = requireNotNull(deviceName),
        createdAt = requireNotNull(createdAt),
        lastUsedAt = lastUsedAt,
    )
