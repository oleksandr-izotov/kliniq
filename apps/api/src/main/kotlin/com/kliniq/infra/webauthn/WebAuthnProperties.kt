package com.kliniq.infra.webauthn

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * Typesafe binding of `app.webauthn.*` from the YAML. Exposes the relying
 * party identity, the origins the browser may launch a ceremony from, and
 * the two timeouts we care about (server-side challenge TTL plus the
 * browser-side ceremony deadline that goes into the options JSON).
 */
@ConfigurationProperties(prefix = "app.webauthn")
data class WebAuthnProperties(
    val rpId: String,
    val rpName: String,
    val origins: List<String>,
    val challengeTtl: Duration,
    val ceremonyTimeout: Duration,
) {
    init {
        require(rpId.isNotBlank()) { "app.webauthn.rp-id must be non-blank" }
        require(rpName.isNotBlank()) { "app.webauthn.rp-name must be non-blank" }
        require(origins.isNotEmpty()) { "app.webauthn.origins must list at least one origin" }
        origins.forEach { o ->
            require(o.startsWith("https://") || o.startsWith("http://localhost")) {
                "app.webauthn.origins entries must use HTTPS (or http://localhost for dev): $o"
            }
        }
        require(challengeTtl > Duration.ZERO) { "challenge-ttl must be positive" }
        require(ceremonyTimeout > Duration.ZERO) { "ceremony-timeout must be positive" }
    }
}
