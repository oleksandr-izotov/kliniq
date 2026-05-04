package com.kliniq.api.auth

import com.kliniq.usecase.auth.RegisterUseCase
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/auth")
class AuthController(
    private val registerUseCase: RegisterUseCase,
) {
    /**
     * Always returns 200 with a neutral message — never reveals whether the
     * email already exists. The use case decides internally.
     */
    @PostMapping("/register")
    @ResponseStatus(HttpStatus.OK)
    fun register(
        @Valid @RequestBody request: RegisterRequest,
    ): RegisterResponse {
        registerUseCase.register(
            RegisterUseCase.RegisterCommand(
                email = request.email.trim(),
                password = request.password,
                displayName = request.displayName.trim(),
            ),
        )
        return RegisterResponse(
            message = "If this email is available, a verification link is on its way.",
        )
    }
}
