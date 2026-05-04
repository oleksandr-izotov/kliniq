package com.kliniq.config

import org.springframework.boot.actuate.autoconfigure.security.servlet.EndpointRequest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.web.SecurityFilterChain

// Sprint 1 Day 7 baseline:
//   - Actuator health/info: public
//   - /api/v1/auth/**: public (register, verify, forgot, reset, login, ...)
//   - everything else: 401 until session-based auth lands in Day 8
// CSRF stays disabled until Day 9 introduces the double-submit cookie pattern;
// until then there are no authenticated state-changing endpoints to protect.
@Configuration
class SecurityConfig {
    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain =
        http
            .authorizeHttpRequests {
                it.requestMatchers(EndpointRequest.to("health", "info")).permitAll()
                it.requestMatchers("/api/v1/auth/**").permitAll()
                it.anyRequest().authenticated()
            }.csrf { it.disable() }
            .formLogin { it.disable() }
            .httpBasic { it.disable() }
            .build()
}
