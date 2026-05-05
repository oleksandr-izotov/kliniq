package com.kliniq.usecase.passkey

import com.kliniq.domain.passkey.Passkey
import com.kliniq.infra.audit.AuditEntry
import com.kliniq.infra.audit.AuditWriter
import com.kliniq.persistence.passkey.PasskeyRepository
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Read-only listing of the current user's passkeys, ordered newest-first
 * by the repository.
 */
@Service
class ListPasskeysUseCase(
    private val passkeys: PasskeyRepository,
) {
    fun list(userId: UUID): List<Passkey> = passkeys.findByUserId(userId)
}

/**
 * Rename a passkey the current user owns. Cross-user attempts return
 * NotFound rather than Forbidden so the API never reveals a credential
 * belongs to someone else.
 */
@Service
class RenamePasskeyUseCase(
    private val passkeys: PasskeyRepository,
    private val auditWriter: AuditWriter,
) {
    fun rename(
        userId: UUID,
        passkeyId: UUID,
        newDeviceName: String,
    ): Result {
        val updated = passkeys.rename(passkeyId, userId, newDeviceName.trim())
        if (!updated) return Result.NotFound
        auditWriter.record(
            AuditEntry(
                action = "passkey.renamed",
                entityType = "passkey",
                entityId = passkeyId,
                actorUserId = userId,
                metadata = mapOf("deviceName" to newDeviceName.trim()),
            ),
        )
        return Result.Success
    }

    sealed interface Result {
        data object Success : Result

        data object NotFound : Result
    }
}

/**
 * Delete a passkey the current user owns. Doesn't bother with
 * "are you sure" prompting — that's UI concern.
 */
@Service
class RevokePasskeyUseCase(
    private val passkeys: PasskeyRepository,
    private val auditWriter: AuditWriter,
) {
    fun revoke(
        userId: UUID,
        passkeyId: UUID,
    ): Result {
        val deleted = passkeys.delete(passkeyId, userId)
        if (!deleted) return Result.NotFound
        auditWriter.record(
            AuditEntry(
                action = "passkey.revoked",
                entityType = "passkey",
                entityId = passkeyId,
                actorUserId = userId,
            ),
        )
        return Result.Success
    }

    sealed interface Result {
        data object Success : Result

        data object NotFound : Result
    }
}
