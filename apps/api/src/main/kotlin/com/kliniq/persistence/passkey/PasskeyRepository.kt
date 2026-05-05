package com.kliniq.persistence.passkey

import com.kliniq.domain.passkey.NewPasskey
import com.kliniq.domain.passkey.Passkey
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Persistence-layer port for [Passkey]. Implementations must:
 *   - look credentials up by raw credential id bytes (the WebAuthn assertion
 *     carries the id in `rawId` — we never expose the surrogate UUID outside
 *     our own DB);
 *   - never expose [com.kliniq.db] generated types beyond this layer.
 */
interface PasskeyRepository {
    fun create(newPasskey: NewPasskey): Passkey

    /**
     * Locate a credential by its raw WebAuthn credential id. Returns null if
     * the credential is unknown — the authentication ceremony then fails with
     * INVALID_CREDENTIAL without revealing whether the user exists.
     */
    fun findByCredentialId(credentialId: ByteArray): Passkey?

    /** Lookup by application-level surrogate id, scoped to the owning user. */
    fun findByIdAndUserId(
        id: UUID,
        userId: UUID,
    ): Passkey?

    /** All credentials registered to a user, newest first. Used by the management UI. */
    fun findByUserId(userId: UUID): List<Passkey>

    /**
     * Update the signature counter and stamp `last_used_at` after a
     * successful authentication. Returns true if a row was updated.
     */
    fun updateAfterUse(
        id: UUID,
        signatureCounter: Long,
        lastUsedAt: OffsetDateTime,
    ): Boolean

    /** Change the human-readable device name. Returns true if a row was updated. */
    fun rename(
        id: UUID,
        userId: UUID,
        newDeviceName: String,
    ): Boolean

    /** Remove the passkey. Returns true if a row was deleted. */
    fun delete(
        id: UUID,
        userId: UUID,
    ): Boolean
}
