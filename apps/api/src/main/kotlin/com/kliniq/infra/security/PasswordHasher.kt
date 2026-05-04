package com.kliniq.infra.security

import de.mkammerer.argon2.Argon2Factory
import de.mkammerer.argon2.Argon2Factory.Argon2Types
import org.springframework.stereotype.Component

/**
 * Argon2id password hasher. Parameters per ADR-010 — re-evaluate annually
 * against current hardware. The cost is intentionally ~100ms per hash on a
 * modern laptop; that's the right order of magnitude for user-facing login.
 *
 *   memoryKb     = 64 MiB
 *   iterations   = 3
 *   parallelism  = 4
 */
@Component
class PasswordHasher {
    private val argon2 = Argon2Factory.create(Argon2Types.ARGON2id)

    fun hash(rawPassword: CharArray): String =
        argon2
            .hash(ITERATIONS, MEMORY_KB, PARALLELISM, rawPassword)
            .also { argon2.wipeArray(rawPassword) }

    /** Convenience overload that wipes the input string's characters after hashing. */
    fun hash(rawPassword: String): String = hash(rawPassword.toCharArray())

    fun matches(
        rawPassword: CharArray,
        encodedHash: String,
    ): Boolean =
        try {
            argon2.verify(encodedHash, rawPassword)
        } finally {
            argon2.wipeArray(rawPassword)
        }

    fun matches(
        rawPassword: String,
        encodedHash: String,
    ): Boolean = matches(rawPassword.toCharArray(), encodedHash)

    companion object {
        private const val MEMORY_KB = 64 * 1024 // 64 MiB
        private const val ITERATIONS = 3
        private const val PARALLELISM = 4
    }
}
