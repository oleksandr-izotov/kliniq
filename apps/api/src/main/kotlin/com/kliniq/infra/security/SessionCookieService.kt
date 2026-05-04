package com.kliniq.infra.security

import jakarta.servlet.http.Cookie
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.ResponseCookie
import org.springframework.stereotype.Component

/**
 * Centralises every place we read or write the session cookie. Using
 * `__Host-` as a prefix forces the browser to enforce three properties:
 *   - Secure (HTTPS only)
 *   - Path = /
 *   - no Domain attribute
 * Combined with HttpOnly + SameSite=Lax this is the OWASP-recommended
 * cookie shape for session-based auth (ADR-004).
 */
@Component
class SessionCookieService {
    fun read(request: HttpServletRequest): String? = request.cookies?.firstOrNull { it.name == COOKIE_NAME }?.value

    fun write(
        response: HttpServletResponse,
        sessionId: String,
    ) {
        val cookie =
            ResponseCookie
                .from(COOKIE_NAME, sessionId)
                .httpOnly(true)
                .secure(true)
                .sameSite("Lax")
                .path("/")
                .maxAge(COOKIE_TTL)
                .build()
        response.addHeader("Set-Cookie", cookie.toString())
    }

    fun clear(response: HttpServletResponse) {
        // Setting Max-Age=0 with the same attributes evicts the cookie. The
        // browser treats it as an expiry signal regardless of any earlier
        // Set-Cookie for the same name on this domain.
        val cleared =
            ResponseCookie
                .from(COOKIE_NAME, "")
                .httpOnly(true)
                .secure(true)
                .sameSite("Lax")
                .path("/")
                .maxAge(0)
                .build()
        response.addHeader("Set-Cookie", cleared.toString())
        // Defensive: also kill any leftover javax cookie with the same name.
        Cookie(COOKIE_NAME, "")
            .apply {
                path = "/"
                maxAge = 0
                isHttpOnly = true
                secure = true
            }.also(response::addCookie)
    }

    companion object {
        const val COOKIE_NAME = "__Host-kliniq_session"

        // Mirror the SessionStore TTL — see RedisSessionStore.TTL.
        private val COOKIE_TTL: java.time.Duration = java.time.Duration.ofDays(30)
    }
}
