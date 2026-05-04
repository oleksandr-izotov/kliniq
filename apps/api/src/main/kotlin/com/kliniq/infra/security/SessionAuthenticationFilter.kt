package com.kliniq.infra.security

import com.kliniq.persistence.user.UserRepository
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Reads the session cookie on each incoming request and, if a matching
 * session exists in Redis, sets a [KliniqAuthentication] in the
 * SecurityContext. The filter never short-circuits — failed lookups just
 * leave the context anonymous so the rest of the security chain decides
 * whether the request needs auth (per `SecurityConfig`).
 */
@Component
class SessionAuthenticationFilter(
    private val sessions: SessionStore,
    private val cookies: SessionCookieService,
    private val users: UserRepository,
) : OncePerRequestFilter() {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val sessionId = cookies.read(request)
        if (sessionId != null && SecurityContextHolder.getContext().authentication == null) {
            authenticate(sessionId)
        }
        filterChain.doFilter(request, response)
    }

    @Suppress("ReturnCount") // early returns are clearer than nested guard branches
    private fun authenticate(sessionId: String) {
        val session = sessions.find(sessionId) ?: return
        val user = users.findById(session.userId)
        if (user == null) {
            // Orphaned session — user was deleted. Clean it up so we don't
            // keep doing useless lookups and so the next request loses its cookie.
            log.warn("session {} references missing user {}; invalidating", sessionId, session.userId)
            sessions.invalidate(sessionId)
            return
        }
        if (!user.isActive) {
            log.warn("session {} belongs to disabled user {}; invalidating", sessionId, user.id)
            sessions.invalidate(sessionId)
            return
        }
        SecurityContextHolder.getContext().authentication = KliniqAuthentication(user, session)
    }
}
