package com.kliniq.infra.security

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * Per-IP exponential backoff layered on top of [com.kliniq.infra.ratelimit.RateLimitFilter].
 * The plain rate limiter is a hard ceiling (10 login attempts per minute);
 * this tracker adds the OWASP ASVS V13.2.6 *backoff* — each consecutive
 * failure doubles the next allowed attempt time, so a credential-stuffing
 * burst from one source becomes prohibitively slow within a few tries.
 *
 * State (`{failures, nextAllowedAt}`) lives in Redis under
 * `kliniq:login-backoff:{ip}` with a 1-hour TTL: an attacker who stops
 * for an hour gets a fresh budget, which is the right trade-off for
 * legitimate users with intermittent typos.
 *
 * Attempts are intentionally tracked per IP rather than per email so a
 * lockout can't be weaponised: an attacker probing one account can't
 * lock out the legitimate owner from a different IP.
 */
@Component
class LoginAttemptTracker(
    private val redis: StringRedisTemplate,
    private val mapper: ObjectMapper,
    private val clock: Clock,
) {
    /**
     * Returns the [Instant] before which the caller must reject the
     * attempt. If no backoff is in effect, returns [Instant.MIN].
     */
    fun nextAllowedAt(ip: String): Instant = readState(ip)?.nextAllowedAt ?: Instant.MIN

    /**
     * Bump the failure counter and compute the new wait window.
     * Returns the seconds the caller should advertise via Retry-After.
     */
    fun recordFailure(ip: String): Long {
        val current = readState(ip)
        val newFailures = (current?.failures ?: 0) + 1
        val cappedExp = minOf(newFailures - 1, MAX_EXP)
        val backoffSeconds =
            (BASE_BACKOFF_SECONDS shl cappedExp).toLong().coerceAtMost(MAX_BACKOFF_SECONDS)
        val nextAllowed = clock.instant().plusSeconds(backoffSeconds)
        write(ip, State(failures = newFailures, nextAllowedAtEpochMillis = nextAllowed.toEpochMilli()))
        return backoffSeconds
    }

    /** Wipe the counter — the legitimate user has authenticated. */
    fun recordSuccess(ip: String) {
        redis.delete(key(ip))
    }

    private fun readState(ip: String): State? {
        val raw = redis.opsForValue().get(key(ip)) ?: return null
        val state = mapper.readValue(raw, State::class.java)
        // Drop stale state when the wait has already passed but the TTL
        // has yet to expire — keeps recordFailure() honest on long pauses.
        return if (clock.instant().isAfter(state.nextAllowedAt)) null else state
    }

    private fun write(
        ip: String,
        state: State,
    ) {
        redis.opsForValue().set(key(ip), mapper.writeValueAsString(state), STATE_TTL)
    }

    private fun key(ip: String) = "$KEY_PREFIX$ip"

    /** Wire format. Public so Jackson can read it back. */
    data class State
        @JsonCreator
        constructor(
            @JsonProperty("failures") val failures: Int,
            @JsonProperty("nextAllowedAtEpochMillis") val nextAllowedAtEpochMillis: Long,
        ) {
            val nextAllowedAt: Instant get() = Instant.ofEpochMilli(nextAllowedAtEpochMillis)
        }

    companion object {
        const val KEY_PREFIX = "kliniq:login-backoff:"

        private const val BASE_BACKOFF_SECONDS = 2 // first failure → 2s, then 4, 8, 16, …
        private const val MAX_BACKOFF_SECONDS = 60L
        private const val MAX_EXP = 30 // defensive: caps newFailures-1 before shl
        private val STATE_TTL: Duration = Duration.ofHours(1)
    }
}
