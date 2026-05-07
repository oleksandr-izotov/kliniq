package com.kliniq.api.auth

import com.kliniq.api.error.ApiErrorResponse
import com.kliniq.infra.security.KliniqAuthentication
import com.kliniq.infra.security.LoginAttemptTracker
import com.kliniq.infra.security.SessionCookieService
import com.kliniq.infra.security.clientIp
import com.kliniq.usecase.auth.ChangePasswordUseCase
import com.kliniq.usecase.auth.ForgotPasswordUseCase
import com.kliniq.usecase.auth.LoginUseCase
import com.kliniq.usecase.auth.LogoutUseCase
import com.kliniq.usecase.auth.RegisterUseCase
import com.kliniq.usecase.auth.ResetPasswordUseCase
import com.kliniq.usecase.auth.VerifyEmailUseCase
import com.kliniq.usecase.invitation.AcceptInvitationUseCase
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/auth")
@Suppress("LongParameterList") // controller orchestrates many use cases via DI
class AuthController(
    private val registerUseCase: RegisterUseCase,
    private val verifyEmailUseCase: VerifyEmailUseCase,
    private val loginUseCase: LoginUseCase,
    private val logoutUseCase: LogoutUseCase,
    private val forgotPasswordUseCase: ForgotPasswordUseCase,
    private val resetPasswordUseCase: ResetPasswordUseCase,
    private val changePasswordUseCase: ChangePasswordUseCase,
    private val acceptInvitationUseCase: AcceptInvitationUseCase,
    private val cookies: SessionCookieService,
    private val loginAttempts: LoginAttemptTracker,
    private val clock: java.time.Clock,
) {
    /**
     * Returns 200 with a neutral message on success — never reveals whether
     * the email already exists. Returns 400 PASSWORD_BREACHED only when the
     * candidate password fails the breach check; that rejection is
     * independent of email state and so doesn't leak user existence.
     */
    @PostMapping("/register")
    fun register(
        @Valid @RequestBody request: RegisterRequest,
    ): ResponseEntity<*> {
        val result =
            registerUseCase.register(
                RegisterUseCase.RegisterCommand(
                    email = request.email.trim(),
                    password = request.password,
                    displayName = request.displayName.trim(),
                ),
            )
        return when (result) {
            RegisterUseCase.Result.Accepted ->
                ResponseEntity.ok(
                    MessageResponse("If this email is available, a verification link is on its way."),
                )
            RegisterUseCase.Result.PasswordBreached ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                    ApiErrorResponse(
                        code = "PASSWORD_BREACHED",
                        message =
                            "This password has appeared in a known data breach. " +
                                "Choose a different one.",
                    ),
                )
        }
    }

    @PostMapping("/verify")
    fun verify(
        @Valid @RequestBody request: VerifyRequest,
    ): ResponseEntity<*> =
        when (verifyEmailUseCase.verify(request.token)) {
            VerifyEmailUseCase.Result.Verified ->
                ResponseEntity.ok(MessageResponse("Email verified. You can sign in now."))
            VerifyEmailUseCase.Result.InvalidToken ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                    ApiErrorResponse(
                        code = "INVALID_TOKEN",
                        message = "This verification link is invalid or has expired.",
                    ),
                )
        }

    @PostMapping("/login")
    fun login(
        @Valid @RequestBody request: LoginRequest,
        httpRequest: HttpServletRequest,
        response: HttpServletResponse,
    ): ResponseEntity<*> {
        val ip = httpRequest.clientIp()

        // Exponential backoff (ASVS V13.2.6) — kicks in BEFORE the use case
        // so a doomed attempt doesn't even spend an Argon2 verification.
        val now = clock.instant()
        val nextAllowed = loginAttempts.nextAllowedAt(ip)
        if (now.isBefore(nextAllowed)) {
            val retryAfterSeconds =
                java.time.Duration
                    .between(now, nextAllowed)
                    .seconds
                    .coerceAtLeast(1)
            return ResponseEntity
                .status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", retryAfterSeconds.toString())
                .body(
                    ApiErrorResponse(
                        code = "LOGIN_BACKOFF",
                        message = "Too many failed sign-in attempts. Try again shortly.",
                    ),
                )
        }

        return when (val result = loginUseCase.login(request.email.trim(), request.password)) {
            is LoginUseCase.Result.Success -> {
                loginAttempts.recordSuccess(ip)
                cookies.write(response, result.session.id)
                ResponseEntity.ok(UserResponse.of(result.user))
            }
            LoginUseCase.Result.InvalidCredentials -> {
                val retryAfterSeconds = loginAttempts.recordFailure(ip)
                ResponseEntity
                    .status(HttpStatus.UNAUTHORIZED)
                    .header("Retry-After", retryAfterSeconds.toString())
                    .body(
                        ApiErrorResponse(
                            code = "INVALID_CREDENTIALS",
                            message = "Email or password is incorrect.",
                        ),
                    )
            }
            LoginUseCase.Result.EmailNotVerified ->
                ResponseEntity.status(HttpStatus.FORBIDDEN).body(
                    ApiErrorResponse(
                        code = "EMAIL_NOT_VERIFIED",
                        message = "Please verify your email before signing in.",
                    ),
                )
            LoginUseCase.Result.AccountDisabled ->
                ResponseEntity.status(HttpStatus.FORBIDDEN).body(
                    ApiErrorResponse(
                        code = "ACCOUNT_DISABLED",
                        message = "This account has been disabled.",
                    ),
                )
        }
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.OK)
    fun logout(
        request: HttpServletRequest,
        response: HttpServletResponse,
    ): MessageResponse {
        cookies.read(request)?.let(logoutUseCase::logout)
        cookies.clear(response)
        SecurityContextHolder.clearContext()
        return MessageResponse("Signed out.")
    }

    /**
     * Always returns 200 with a neutral message — never reveals whether the
     * email belongs to a registered user.
     */
    @PostMapping("/password/forgot")
    @ResponseStatus(HttpStatus.OK)
    fun forgotPassword(
        @Valid @RequestBody request: ForgotPasswordRequest,
    ): MessageResponse {
        forgotPasswordUseCase.forgot(request.email.trim())
        return MessageResponse(
            message = "If this email is registered and verified, a reset link is on its way.",
        )
    }

    /**
     * In-app password change. Authenticated; requires the current password
     * to be presented again (V3.7.1). Other sessions belonging to this
     * user are terminated; the current one stays alive.
     */
    @PostMapping("/password/change")
    fun changePassword(
        @Valid @RequestBody request: ChangePasswordRequest,
    ): ResponseEntity<*> {
        val auth =
            SecurityContextHolder.getContext().authentication as? KliniqAuthentication
                ?: error("authenticated endpoint reached without KliniqAuthentication")
        return when (
            val result =
                changePasswordUseCase.change(
                    userId = auth.user.id,
                    currentSessionId = auth.session.id,
                    currentPassword = request.currentPassword,
                    newPassword = request.newPassword,
                )
        ) {
            is ChangePasswordUseCase.Result.Success ->
                ResponseEntity.ok(
                    MessageResponse(
                        if (result.otherSessionsTerminated == 0) {
                            "Password updated."
                        } else {
                            "Password updated. ${result.otherSessionsTerminated} other " +
                                "device${if (result.otherSessionsTerminated == 1) "" else "s"} signed out."
                        },
                    ),
                )
            ChangePasswordUseCase.Result.IncorrectCurrentPassword ->
                ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(
                    ApiErrorResponse(
                        code = "INVALID_CREDENTIALS",
                        message = "The current password is incorrect.",
                    ),
                )
            ChangePasswordUseCase.Result.PasswordBreached ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                    ApiErrorResponse(
                        code = "PASSWORD_BREACHED",
                        message =
                            "This password has appeared in a known data breach. " +
                                "Choose a different one.",
                    ),
                )
            ChangePasswordUseCase.Result.SamePassword ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                    ApiErrorResponse(
                        code = "SAME_PASSWORD",
                        message = "The new password must differ from the current one.",
                    ),
                )
            ChangePasswordUseCase.Result.PasskeyOnlyAccount ->
                ResponseEntity.status(HttpStatus.CONFLICT).body(
                    ApiErrorResponse(
                        code = "NO_PASSWORD",
                        message = "This account has no password to change. Use the reset link instead.",
                    ),
                )
        }
    }

    @PostMapping("/password/reset")
    fun resetPassword(
        @Valid @RequestBody request: ResetPasswordRequest,
    ): ResponseEntity<*> =
        when (resetPasswordUseCase.reset(request.token, request.newPassword)) {
            ResetPasswordUseCase.Result.Success ->
                ResponseEntity.ok(MessageResponse("Password updated. You can sign in with your new password."))
            ResetPasswordUseCase.Result.InvalidToken ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                    ApiErrorResponse(
                        code = "INVALID_TOKEN",
                        message = "This reset link is invalid or has expired.",
                    ),
                )
            ResetPasswordUseCase.Result.PasswordBreached ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                    ApiErrorResponse(
                        code = "PASSWORD_BREACHED",
                        message =
                            "This password has appeared in a known data breach. " +
                                "Choose a different one.",
                    ),
                )
        }

    /**
     * Returns the currently authenticated user. The SessionAuthenticationFilter
     * populates the SecurityContext on every request — if it didn't, this
     * endpoint is unreachable thanks to `anyRequest().authenticated()` in
     * SecurityConfig (Spring returns 403 before this method runs).
     */
    @GetMapping("/me")
    fun me(): UserResponse {
        val auth =
            SecurityContextHolder.getContext().authentication as? KliniqAuthentication
                ?: error("Authenticated request reached /me without KliniqAuthentication")
        return UserResponse.of(auth.user)
    }

    /**
     * Public endpoint: redeem an admin-issued invitation token, create the
     * pre-verified user with the role + surgeon flag the admin set, and
     * log them in. Each failure mode maps to a distinct code so the
     * accept page can show actionable copy.
     */
    @PostMapping("/invitation/accept")
    @Suppress("CyclomaticComplexMethod") // one branch per Result variant
    fun acceptInvitation(
        @Valid @RequestBody request: AcceptInvitationRequest,
        response: HttpServletResponse,
    ): ResponseEntity<*> =
        when (
            val result =
                acceptInvitationUseCase.accept(
                    AcceptInvitationUseCase.Command(
                        token = request.token,
                        password = request.password,
                        displayName = request.displayName,
                    ),
                )
        ) {
            is AcceptInvitationUseCase.Result.Success -> {
                cookies.write(response, result.session.id)
                ResponseEntity.ok(UserResponse.of(result.user))
            }
            is AcceptInvitationUseCase.Result.InvalidInput ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                    ApiErrorResponse(code = "VALIDATION_ERROR", message = result.reason),
                )
            AcceptInvitationUseCase.Result.InvalidToken ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                    ApiErrorResponse(code = "INVALID_TOKEN", message = "Invitation link is invalid."),
                )
            AcceptInvitationUseCase.Result.Expired ->
                ResponseEntity.status(HttpStatus.GONE).body(
                    ApiErrorResponse(code = "INVITATION_EXPIRED", message = "Invitation has expired."),
                )
            AcceptInvitationUseCase.Result.Revoked ->
                ResponseEntity.status(HttpStatus.GONE).body(
                    ApiErrorResponse(code = "INVITATION_REVOKED", message = "Invitation has been revoked."),
                )
            AcceptInvitationUseCase.Result.AlreadyAccepted ->
                ResponseEntity.status(HttpStatus.CONFLICT).body(
                    ApiErrorResponse(
                        code = "INVITATION_ALREADY_ACCEPTED",
                        message = "This invitation has already been used.",
                    ),
                )
            AcceptInvitationUseCase.Result.UserAlreadyExists ->
                ResponseEntity.status(HttpStatus.CONFLICT).body(
                    ApiErrorResponse(
                        code = "USER_ALREADY_EXISTS",
                        message =
                            "A user with that email already exists. Sign in normally instead.",
                    ),
                )
            AcceptInvitationUseCase.Result.PasswordBreached ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                    ApiErrorResponse(
                        code = "PASSWORD_BREACHED",
                        message =
                            "This password has appeared in a known data breach. " +
                                "Choose a different one.",
                    ),
                )
        }
}
