package com.kliniq.api.auth

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

data class RegisterRequest(
    @field:NotBlank
    @field:Email
    @field:Size(max = MAX_EMAIL)
    val email: String,
    @field:NotBlank
    @field:Size(min = MIN_PASSWORD, max = MAX_PASSWORD)
    val password: String,
    @field:NotBlank
    @field:Size(min = 1, max = MAX_DISPLAY_NAME)
    val displayName: String,
) {
    companion object {
        const val MAX_EMAIL = 254 // RFC 5321 max
        const val MIN_PASSWORD = 12 // OWASP ASVS V2.1.1
        const val MAX_PASSWORD = 128 // ASVS V2.1.2 — allow long passphrases
        const val MAX_DISPLAY_NAME = 100
    }
}

data class RegisterResponse(
    val message: String,
)
