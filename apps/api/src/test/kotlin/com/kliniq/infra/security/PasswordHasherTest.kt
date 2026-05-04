package com.kliniq.infra.security

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class PasswordHasherTest {
    private val hasher = PasswordHasher()

    @Test
    fun `hash produces an Argon2id encoded string`() {
        val hash = hasher.hash("correct horse battery staple")

        // Argon2id encoded form starts with $argon2id$v=19$...
        assertThat(hash).startsWith("\$argon2id\$")
        assertThat(hash).contains("\$v=19\$")
    }

    @Test
    fun `matches returns true for the original password`() {
        val hash = hasher.hash("correct horse battery staple")

        assertThat(hasher.matches("correct horse battery staple", hash)).isTrue()
    }

    @Test
    fun `matches returns false for the wrong password`() {
        val hash = hasher.hash("correct horse battery staple")

        assertThat(hasher.matches("incorrect horse battery staple", hash)).isFalse()
    }

    @Test
    fun `same input produces different hashes (salt is random)`() {
        val a = hasher.hash("samepassword")
        val b = hasher.hash("samepassword")

        assertThat(a).isNotEqualTo(b)
        assertThat(hasher.matches("samepassword", a)).isTrue()
        assertThat(hasher.matches("samepassword", b)).isTrue()
    }
}
