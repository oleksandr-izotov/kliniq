package com.kliniq.api.auth

import com.kliniq.api.error.ApiErrorResponse
import com.kliniq.infra.security.KliniqAuthentication
import com.kliniq.infra.security.SessionCookieService
import com.kliniq.usecase.auth.ForgotPasswordUseCase
import com.kliniq.usecase.auth.LoginUseCase
import com.kliniq.usecase.auth.LogoutUseCase
import com.kliniq.usecase.auth.RegisterUseCase
import com.kliniq.usecase.auth.ResetPasswordUseCase
import com.kliniq.usecase.auth.VerifyEmailUseCase
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
    private val cookies: SessionCookieService,
) {
    /**
     * Always returns 200 with a neutral message — never reveals whether the
     * email already exists. The use case decides internally.
     */
    @PostMapping("/register")
    @ResponseStatus(HttpStatus.OK)
    fun register(
        @Valid @RequestBody request: RegisterRequest,
    ): MessageResponse {
        registerUseCase.register(
            RegisterUseCase.RegisterCommand(
                email = request.email.trim(),
                password = request.password,
                displayName = request.displayName.trim(),
            ),
        )
        return MessageResponse(
            message = "If this email is available, a verification link is on its way.",
        )
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
        response: HttpServletResponse,
    ): ResponseEntity<*> =
        when (val result = loginUseCase.login(request.email.trim(), request.password)) {
            is LoginUseCase.Result.Success -> {
                cookies.write(response, result.session.id)
                ResponseEntity.ok(UserResponse.of(result.user))
            }
            LoginUseCase.Result.InvalidCredentials ->
                ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(
                    ApiErrorResponse(
                        code = "INVALID_CREDENTIALS",
                        message = "Email or password is incorrect.",
                    ),
                )
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

    @PostMapping("/password/reset")
    fun resetPassword(
        @Valid @RequestBody request: ResetPasswordRequest,
    ): ResponseEntity<*> =
        when (resetPasswordUseCase.reset(request.token, request.newPassword)) {
            ResetPasswordUseCase.Result.Success ->
                ResponseEntity.ok(MessageResponse("Password updated. You can sign in with your new password."))
            ResetPasswordUseCase.Result.InvalidToken ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                    com.kliniq.api.error.ApiErrorResponse(
                        code = "INVALID_TOKEN",
                        message = "This reset link is invalid or has expired.",
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
}
