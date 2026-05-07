package com.kliniq.infra.ratelimit

import com.kliniq.infra.security.clientIp
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.annotation.Order
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.time.Duration

/**
 * Per-IP, per-endpoint rate limiting using a Redis INCR counter with an
 * expire. The atomic INCR + first-write-wins EXPIRE pattern is the
 * canonical Redis recipe — no Lua, no race conditions.
 *
 * Limits per ASVS V13.2.6 / SPRINT_1.md:
 *   POST /api/v1/auth/register        → 5 / min / IP
 *   POST /api/v1/auth/login           → 10 / min / IP
 *   POST /api/v1/auth/password/forgot → 3 / min / IP
 *
 * The filter runs early in the chain so rate-limited requests bypass the
 * heavier session lookup and Argon2 verification downstream.
 */
@Component
@Order(RateLimitFilter.FILTER_ORDER) // run after Spring Security but before the controller
class RateLimitFilter(
    private val redis: StringRedisTemplate,
) : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val rule = LIMITS["${request.method}:${request.requestURI}"]
        if (rule == null) {
            filterChain.doFilter(request, response)
            return
        }

        val ip = request.clientIp()
        val key = "rate-limit:${request.method}:${request.requestURI}:$ip"
        val count = redis.opsForValue().increment(key) ?: 1
        if (count == 1L) {
            // First request in the window — fire-and-forget the TTL setter.
            redis.expire(key, rule.window)
        }

        if (count > rule.maxRequests) {
            val ttl = redis.getExpire(key)
            writeRateLimited(response, ttl)
            return
        }
        filterChain.doFilter(request, response)
    }

    private fun writeRateLimited(
        response: HttpServletResponse,
        retryAfterSeconds: Long,
    ) {
        response.status = HttpStatus.TOO_MANY_REQUESTS.value()
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        if (retryAfterSeconds > 0) response.setHeader("Retry-After", retryAfterSeconds.toString())
        response.writer.write(
            """{"code":"RATE_LIMITED","message":"Too many requests, please try again shortly."}""",
        )
    }

    /** Fixed limits for V1. Move to @ConfigurationProperties when we add tunables. */
    private data class Rule(
        val maxRequests: Long,
        val window: Duration,
    )

    companion object {
        // Run after Spring Security's filter chain but before the controller.
        const val FILTER_ORDER: Int = Int.MAX_VALUE - 100

        private val LIMITS: Map<String, Rule> =
            mapOf(
                "POST:/api/v1/auth/register" to Rule(5, Duration.ofMinutes(1)),
                "POST:/api/v1/auth/login" to Rule(10, Duration.ofMinutes(1)),
                "POST:/api/v1/auth/password/forgot" to Rule(3, Duration.ofMinutes(1)),
                // /password/change is auth-required; capping per IP also
                // throttles a session-stealing attacker probing the
                // current-password requirement.
                "POST:/api/v1/auth/password/change" to Rule(10, Duration.ofMinutes(1)),
                // Passkey ceremony endpoints. /begin is cheap (random bytes
                // + Redis SET) so a generous quota is fine; /finish runs
                // signature verification, so we tighten to login-class limits.
                "POST:/api/v1/auth/passkeys/authentication/begin" to Rule(20, Duration.ofMinutes(1)),
                "POST:/api/v1/auth/passkeys/authentication/finish" to Rule(10, Duration.ofMinutes(1)),
                "POST:/api/v1/auth/passkeys/registration/begin" to Rule(10, Duration.ofMinutes(1)),
                "POST:/api/v1/auth/passkeys/registration/finish" to Rule(10, Duration.ofMinutes(1)),
                // Booking writes — staff in a busy clinic plausibly create
                // tens of bookings per minute during scheduling sessions, so
                // generous-but-bounded.
                "POST:/api/v1/bookings" to Rule(60, Duration.ofMinutes(1)),
                // Operating-room writes are rarer.
                "POST:/api/v1/operating-rooms" to Rule(30, Duration.ofMinutes(1)),
                // SSE: opening many EventSource streams per IP is not a
                // normal pattern. Reverse proxies + native auto-reconnect
                // mean a healthy session reconnects sparingly.
                "GET:/api/v1/events" to Rule(5, Duration.ofMinutes(1)),
            )
    }
}
