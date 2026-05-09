package com.kliniq.api.auth

import com.kliniq.domain.user.Role
import com.kliniq.domain.user.Specialty
import com.kliniq.domain.user.User
import com.kliniq.domain.user.UserStatus
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.OffsetDateTime
import java.util.UUID

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

data class LoginRequest(
    @field:NotBlank
    @field:Email
    @field:Size(max = RegisterRequest.MAX_EMAIL)
    val email: String,
    @field:NotBlank
    @field:Size(min = RegisterRequest.MIN_PASSWORD, max = RegisterRequest.MAX_PASSWORD)
    val password: String,
)

data class VerifyRequest(
    @field:NotBlank
    @field:Size(min = 1, max = MAX_TOKEN)
    val token: String,
) {
    companion object {
        const val MAX_TOKEN = 100 // 32 bytes base64url-encoded fits in 43 chars; cap is a sanity bound
    }
}

data class ForgotPasswordRequest(
    @field:NotBlank
    @field:Email
    @field:Size(max = RegisterRequest.MAX_EMAIL)
    val email: String,
)

data class ResetPasswordRequest(
    @field:NotBlank
    @field:Size(min = 1, max = VerifyRequest.MAX_TOKEN)
    val token: String,
    @field:NotBlank
    @field:Size(min = RegisterRequest.MIN_PASSWORD, max = RegisterRequest.MAX_PASSWORD)
    val newPassword: String,
)

data class ChangePasswordRequest(
    @field:NotBlank
    @field:Size(min = 1, max = RegisterRequest.MAX_PASSWORD)
    val currentPassword: String,
    @field:NotBlank
    @field:Size(min = RegisterRequest.MIN_PASSWORD, max = RegisterRequest.MAX_PASSWORD)
    val newPassword: String,
)

/** PATCH /auth/me — V1 only carries displayName; future fields land here. */
data class UpdateProfileRequest(
    @field:NotBlank
    @field:Size(min = 1, max = RegisterRequest.MAX_DISPLAY_NAME)
    val displayName: String,
)

data class MessageResponse(
    val message: String,
)

/** Shape returned by /login (success), /me, and /verify when we want to echo the user. */
data class UserResponse(
    val id: UUID,
    val email: String,
    val displayName: String,
    val role: Role,
    val isSurgeon: Boolean,
    val specialty: Specialty?,
    val status: UserStatus,
    val emailVerifiedAt: OffsetDateTime?,
    val createdAt: OffsetDateTime,
) {
    companion object {
        fun of(user: User): UserResponse =
            UserResponse(
                id = user.id,
                email = user.email,
                displayName = user.displayName,
                role = user.role,
                isSurgeon = user.isSurgeon,
                specialty = user.specialty,
                status = user.status,
                emailVerifiedAt = user.emailVerifiedAt,
                createdAt = user.createdAt,
            )
    }
}

/**
 * Public accept-invitation flow. Token is the plaintext from the email
 * link; the backend hashes it before lookup and never echoes it back.
 */
data class AcceptInvitationRequest(
    @field:jakarta.validation.constraints.NotBlank
    val token: String,
    @field:jakarta.validation.constraints.NotBlank
    @field:jakarta.validation.constraints.Size(min = 12, max = 128)
    val password: String,
    @field:jakarta.validation.constraints.NotBlank
    @field:jakarta.validation.constraints.Size(min = 1, max = 100)
    val displayName: String,
)

/** Read-only preview the SPA loads on the /invite page before the form is filled in. */
data class InvitationPreviewRequest(
    @field:jakarta.validation.constraints.NotBlank
    val token: String,
)

data class InvitationPreviewDto(
    val email: String,
    val role: Role,
    val isSurgeon: Boolean,
    val specialty: Specialty?,
    val expiresAt: OffsetDateTime,
)
