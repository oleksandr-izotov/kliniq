package com.kliniq.config

import com.kliniq.infra.security.SessionAuthenticationFilter
import org.springframework.boot.actuate.autoconfigure.security.servlet.EndpointRequest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.access.AccessDeniedHandler
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter

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
                    ).permitAll()
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
            }.csrf { it.disable() }
            .formLogin { it.disable() }
            .httpBasic { it.disable() }
            .build()
}
