package com.kliniq.domain.passkey

import java.time.OffsetDateTime
import java.util.UUID

/**
 * A registered WebAuthn credential bound to a [com.kliniq.domain.user.User].
 * Mirrors the `passkeys` table — see V2__auth.sql.
 *
 * `publicKey` carries the CBOR-serialized AttestedCredentialData (AAGUID +
 * credential id length + credential id + COSE public key). Storing the full
 * blob lets WebAuthn4J's converters round-trip it without us reaching into
 * its parts. `credentialId` is duplicated as a top-level column so the
 * authentication ceremony can look up the row by indexed exact match.
 *
 * `signatureCounter` is opaque to us — the authenticator increments it on
 * every assertion (or stays at zero for keys that don't support counters).
 * We bump our stored copy after every successful login and reject any
 * assertion whose counter doesn't strictly exceed the stored value, which
 * is the spec-mandated cloned-authenticator detection.
 */
data class Passkey(
    val id: UUID,
    val userId: UUID,
    val credentialId: ByteArray,
    val publicKey: ByteArray,
    val signatureCounter: Long,
    val aaguid: UUID?,
    val deviceName: String,
    val createdAt: OffsetDateTime,
    val lastUsedAt: OffsetDateTime?,
) {
    init {
        require(credentialId.isNotEmpty()) { "credentialId must not be empty" }
        require(publicKey.isNotEmpty()) { "publicKey must not be empty" }
        require(deviceName.isNotBlank() && deviceName.length <= MAX_DEVICE_NAME) {
            "deviceName must be between 1 and $MAX_DEVICE_NAME characters"
        }
        require(signatureCounter >= 0) { "signatureCounter must be non-negative" }
    }

    // Hand-rolled equality because the auto-generated data-class equals would
    // compare ByteArray fields by reference. Two Passkey instances with
    // identical bytes should compare equal so tests and dedup logic behave.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Passkey) return false
        return id == other.id &&
            userId == other.userId &&
            credentialId.contentEquals(other.credentialId) &&
            publicKey.contentEquals(other.publicKey) &&
            signatureCounter == other.signatureCounter &&
            aaguid == other.aaguid &&
            deviceName == other.deviceName &&
            createdAt == other.createdAt &&
            lastUsedAt == other.lastUsedAt
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + userId.hashCode()
        result = 31 * result + credentialId.contentHashCode()
        result = 31 * result + publicKey.contentHashCode()
        result = 31 * result + signatureCounter.hashCode()
        result = 31 * result + (aaguid?.hashCode() ?: 0)
        result = 31 * result + deviceName.hashCode()
        result = 31 * result + createdAt.hashCode()
        result = 31 * result + (lastUsedAt?.hashCode() ?: 0)
        return result
    }

    companion object {
        const val MAX_DEVICE_NAME = 100
    }
}

/**
 * Fields the application supplies when persisting a freshly-registered
 * passkey. The DB defaults `signature_counter` to 0 and timestamps it,
 * but we pass the counter explicitly so test assertions and the rare
 * "counter starts non-zero" authenticator both work.
 */
data class NewPasskey(
    val id: UUID,
    val userId: UUID,
    val credentialId: ByteArray,
    val publicKey: ByteArray,
    val signatureCounter: Long,
    val aaguid: UUID?,
    val deviceName: String,
) {
    init {
        require(credentialId.isNotEmpty()) { "credentialId must not be empty" }
        require(publicKey.isNotEmpty()) { "publicKey must not be empty" }
        require(deviceName.isNotBlank() && deviceName.length <= Passkey.MAX_DEVICE_NAME) {
            "deviceName must be between 1 and ${Passkey.MAX_DEVICE_NAME} characters"
        }
        require(signatureCounter >= 0) { "signatureCounter must be non-negative" }
    }
}
