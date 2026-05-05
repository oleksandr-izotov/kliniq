package com.kliniq.infra.security.breach

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * Tunables for the HIBP-backed breach check. Disabled by default in
 * tests so they don't egress to the public API; production defaults
 * leave it on.
 */
@ConfigurationProperties(prefix = "app.security.breach-check")
data class BreachedPasswordProperties(
    val enabled: Boolean = true,
    val apiBase: String = "https://api.pwnedpasswords.com",
    val timeout: Duration = DEFAULT_TIMEOUT,
    val userAgent: String = "Kliniq",
) {
    init {
        require(apiBase.startsWith("https://") || apiBase.startsWith("http://")) {
            "app.security.breach-check.api-base must start with http(s)://"
        }
        require(timeout > Duration.ZERO) { "timeout must be positive" }
        require(userAgent.isNotBlank()) { "user-agent must be non-blank (HIBP TOS requires one)" }
    }

    companion object {
        private val DEFAULT_TIMEOUT: Duration = Duration.ofSeconds(3)
    }
}
