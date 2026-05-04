package com.kliniq.infra.security

import com.kliniq.domain.auth.Session
import com.kliniq.domain.user.User
import org.springframework.security.authentication.AbstractAuthenticationToken
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.authority.SimpleGrantedAuthority

/**
 * Spring Security [org.springframework.security.core.Authentication] that
 * wraps our domain [User] and the [Session] it came from. Controllers can
 * obtain the current user with @AuthenticationPrincipal User or via
 * SecurityContextHolder.getContext().authentication.principal.
 */
class KliniqAuthentication(
    val user: User,
    val session: Session,
) : AbstractAuthenticationToken(authoritiesFor(user)) {
    init {
        // Authenticated by design — only constructed by SessionAuthenticationFilter
        // after a verified session lookup.
        isAuthenticated = true
    }

    override fun getPrincipal(): User = user

    override fun getCredentials(): Any? = null

    override fun getName(): String = user.email

    companion object {
        private fun authoritiesFor(user: User): List<GrantedAuthority> {
            val role = SimpleGrantedAuthority("ROLE_${user.role.name}")
            return listOf(role)
        }
    }
}
