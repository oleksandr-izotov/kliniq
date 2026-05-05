package com.kliniq.api.passkey

import com.kliniq.api.auth.UserResponse
import com.kliniq.api.error.ApiErrorResponse
import com.kliniq.infra.security.KliniqAuthentication
import com.kliniq.infra.security.SessionCookieService
import com.kliniq.usecase.passkey.AuthenticatePasskeyUseCase
import com.kliniq.usecase.passkey.ListPasskeysUseCase
import com.kliniq.usecase.passkey.RegisterPasskeyUseCase
import com.kliniq.usecase.passkey.RenamePasskeyUseCase
import com.kliniq.usecase.passkey.RevokePasskeyUseCase
import jakarta.servlet.http.HttpServletResponse
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/v1/auth/passkeys")
@Suppress("LongParameterList") // a controller orchestrates many use cases by design
class PasskeyController(
    private val registerUseCase: RegisterPasskeyUseCase,
    private val authenticateUseCase: AuthenticatePasskeyUseCase,
    private val listUseCase: ListPasskeysUseCase,
    private val renameUseCase: RenamePasskeyUseCase,
    private val revokeUseCase: RevokePasskeyUseCase,
    private val cookies: SessionCookieService,
) {
    // -------------------------------------------------------------------------
    // Registration ceremony (logged-in user adds a credential)
    // -------------------------------------------------------------------------

    @PostMapping("/registration/begin")
    fun beginRegistration(): BeginRegistrationResponse = registerUseCase.begin(currentUser())

    @PostMapping("/registration/finish")
    fun finishRegistration(
        @Valid @RequestBody request: FinishRegistrationRequest,
    ): ResponseEntity<*> =
        when (val result = registerUseCase.finish(currentUser(), request)) {
            is RegisterPasskeyUseCase.Result.Success ->
                ResponseEntity.ok(
                    PasskeySummary(
                        id = result.passkey.id,
                        deviceName = result.passkey.deviceName,
                        createdAt = result.passkey.createdAt,
                        lastUsedAt = result.passkey.lastUsedAt,
                    ),
                )
            RegisterPasskeyUseCase.Result.MissingChallenge ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                    ApiErrorResponse(
                        code = "PASSKEY_CHALLENGE_EXPIRED",
                        message = "Registration challenge expired. Start over.",
                    ),
                )
            RegisterPasskeyUseCase.Result.AlreadyRegistered ->
                ResponseEntity.status(HttpStatus.CONFLICT).body(
                    ApiErrorResponse(
                        code = "PASSKEY_ALREADY_REGISTERED",
                        message = "This authenticator is already registered to an account.",
                    ),
                )
            is RegisterPasskeyUseCase.Result.InvalidAttestation ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                    ApiErrorResponse(
                        code = "PASSKEY_INVALID_ATTESTATION",
                        message = "The authenticator response failed verification.",
                    ),
                )
        }

    // -------------------------------------------------------------------------
    // Authentication ceremony (anonymous → sets session cookie)
    // -------------------------------------------------------------------------

    @PostMapping("/authentication/begin")
    fun beginAuthentication(
        @Valid @RequestBody request: BeginAuthenticationRequest,
    ): BeginAuthenticationResponse = authenticateUseCase.begin(request.email)

    @PostMapping("/authentication/finish")
    fun finishAuthentication(
        @Valid @RequestBody request: FinishAuthenticationRequest,
        response: HttpServletResponse,
    ): ResponseEntity<*> =
        when (val result = authenticateUseCase.finish(request)) {
            is AuthenticatePasskeyUseCase.Result.Success -> {
                cookies.write(response, result.session.id)
                ResponseEntity.ok(UserResponse.of(result.user))
            }
            AuthenticatePasskeyUseCase.Result.MissingChallenge ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                    ApiErrorResponse(
                        code = "PASSKEY_CHALLENGE_EXPIRED",
                        message = "Authentication challenge expired. Start over.",
                    ),
                )
            AuthenticatePasskeyUseCase.Result.UnknownCredential ->
                // Same code as InvalidAssertion on purpose — don't reveal whether
                // the credential is registered, the user exists, or the signature
                // checked out.
                ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(
                    ApiErrorResponse(
                        code = "PASSKEY_INVALID",
                        message = "Could not verify this passkey.",
                    ),
                )
            AuthenticatePasskeyUseCase.Result.AccountDisabled ->
                ResponseEntity.status(HttpStatus.FORBIDDEN).body(
                    ApiErrorResponse(
                        code = "ACCOUNT_DISABLED",
                        message = "This account has been disabled.",
                    ),
                )
            is AuthenticatePasskeyUseCase.Result.InvalidAssertion ->
                ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(
                    ApiErrorResponse(
                        code = "PASSKEY_INVALID",
                        message = "Could not verify this passkey.",
                    ),
                )
        }

    // -------------------------------------------------------------------------
    // Management
    // -------------------------------------------------------------------------

    @GetMapping
    fun list(): List<PasskeySummary> =
        listUseCase.list(currentUser().id).map {
            PasskeySummary(
                id = it.id,
                deviceName = it.deviceName,
                createdAt = it.createdAt,
                lastUsedAt = it.lastUsedAt,
            )
        }

    @PatchMapping("/{id}")
    fun rename(
        @PathVariable id: UUID,
        @Valid @RequestBody request: RenamePasskeyRequest,
    ): ResponseEntity<*> =
        when (renameUseCase.rename(currentUser().id, id, request.deviceName)) {
            RenamePasskeyUseCase.Result.Success -> ResponseEntity.noContent().build<Any>()
            RenamePasskeyUseCase.Result.NotFound ->
                ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                    ApiErrorResponse(code = "NOT_FOUND", message = "Passkey not found."),
                )
        }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun revoke(
        @PathVariable id: UUID,
    ): ResponseEntity<*> =
        when (revokeUseCase.revoke(currentUser().id, id)) {
            RevokePasskeyUseCase.Result.Success -> ResponseEntity.noContent().build<Any>()
            RevokePasskeyUseCase.Result.NotFound ->
                ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                    ApiErrorResponse(code = "NOT_FOUND", message = "Passkey not found."),
                )
        }

    private fun currentUser() =
        (SecurityContextHolder.getContext().authentication as? KliniqAuthentication)
            ?.user
            ?: error("Authenticated endpoint reached without KliniqAuthentication")
}
