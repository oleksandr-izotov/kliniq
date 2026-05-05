package com.kliniq.config

import com.kliniq.infra.security.SessionAuthenticationFilter
import org.springframework.boot.actuate.autoconfigure.security.servlet.EndpointRequest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.access.AccessDeniedHandler
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import org.springframework.security.web.csrf.CookieCsrfTokenRepository
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler

// Sprint 1 Day 8 baseline:
//   - Actuator health/info: public
//   - /api/v1/auth/register: public
//   - /api/v1/auth/verify:   public (token comes from email)
//   - /api/v1/auth/login:    public (creates the session)
//   - /api/v1/auth/logout:   public (the session cookie is the only "auth")
//   - /api/v1/auth/me:       authenticated
//   - everything else:       authenticated
//
// CSRF stays disabled until Day 9 introduces the double-submit cookie pattern;
// SameSite=Lax on the session cookie blocks cross-origin POSTs in the meantime.
@Configuration
class SecurityConfig {
    @Bean
    fun securityFilterChain(
        http: HttpSecurity,
        sessionAuthenticationFilter: SessionAuthenticationFilter,
    ): SecurityFilterChain =
        http
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests {
                it.requestMatchers(EndpointRequest.to("health", "info")).permitAll()
                it
                    .requestMatchers(
                        "/api/v1/auth/register",
                        "/api/v1/auth/verify",
                        "/api/v1/auth/login",
                        "/api/v1/auth/logout",
                        "/api/v1/auth/password/forgot",
                        "/api/v1/auth/password/reset",
                        // Passkey authentication ceremony is public by design —
                        // the assertion *is* the proof. Registration/listing/
                        // delete still require an authenticated session.
                        "/api/v1/auth/passkeys/authentication/begin",
                        "/api/v1/auth/passkeys/authentication/finish",
                    ).permitAll()
                // Operating-room write endpoints require MANAGER+ (or ADMIN).
                // Reads stay open to any authenticated user — staff need to
                // see the schedule.
                it
                    .requestMatchers(HttpMethod.POST, "/api/v1/operating-rooms")
                    .hasAnyRole("MANAGER", "ADMIN")
                it
                    .requestMatchers(HttpMethod.PATCH, "/api/v1/operating-rooms/*")
                    .hasAnyRole("MANAGER", "ADMIN")
                it
                    .requestMatchers(HttpMethod.DELETE, "/api/v1/operating-rooms/*")
                    .hasAnyRole("MANAGER", "ADMIN")
                it.anyRequest().authenticated()
            }.addFilterBefore(sessionAuthenticationFilter, UsernamePasswordAuthenticationFilter::class.java)
            .exceptionHandling { ex ->
                // Without authentication → 401 with a clean JSON envelope.
                ex.authenticationEntryPoint { _, response, _ ->
                    response.status = HttpStatus.UNAUTHORIZED.value()
                    response.contentType = MediaType.APPLICATION_JSON_VALUE
                    response.writer.write(
                        """{"code":"UNAUTHENTICATED","message":"Authentication required."}""",
                    )
                }
                // Authenticated but lacking permission → 403 with the same envelope.
                ex.accessDeniedHandler(
                    AccessDeniedHandler { _, response, _ ->
                        response.status = HttpStatus.FORBIDDEN.value()
                        response.contentType = MediaType.APPLICATION_JSON_VALUE
                        response.writer.write(
                            """{"code":"FORBIDDEN","message":"You don't have access to this resource."}""",
                        )
                    },
                )
            }.csrf { csrf ->
                // Double-submit cookie pattern (ADR-004). The cookie is
                // readable by JS — the SPA reads it and echoes the value
                // back in the X-XSRF-TOKEN header on every state-changing
                // request. The unverified-cookie attacker can't forge that
                // value, so cross-site forms can't drive logged-in actions.
                //
                // setCsrfRequestAttributeName(null) forces eager token
                // generation. With Spring 6's default deferred handler the
                // cookie isn't written until something calls getToken() —
                // and our 401 entry point short-circuits the chain before
                // anything does. Eager generation guarantees the SPA can
                // prime the cookie with a single GET regardless of auth.
                val requestHandler = CsrfTokenRequestAttributeHandler()
                requestHandler.setCsrfRequestAttributeName(null)
                csrf
                    .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                    .csrfTokenRequestHandler(requestHandler)
            }.formLogin { it.disable() }
            .httpBasic { it.disable() }
            .build()
}
