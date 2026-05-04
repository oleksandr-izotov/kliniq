package com.kliniq.usecase.auth

import com.kliniq.infra.security.SessionStore
import org.springframework.stereotype.Service

/**
 * Invalidates a session in Redis. Called from the /logout endpoint after the
 * filter has populated the SecurityContext, so the caller already knows the
 * session id from the cookie.
 */
@Service
class LogoutUseCase(
    private val sessions: SessionStore,
) {
    fun logout(sessionId: String) {
        sessions.invalidate(sessionId)
    }
}
