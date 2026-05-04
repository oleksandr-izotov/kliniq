package com.kliniq.infra.security

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import com.kliniq.domain.auth.Session
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.util.Base64
import java.util.UUID

/**
 * Where browser sessions live. The Redis key is `kliniq:session:{id}`. The
 * value is a JSON blob (see [SessionData]). TTL is 30 days but slides on
 * each access — every `find` extends the lifetime, so active users stay
 * logged in indefinitely while idle ones are garbage-collected.
 */
interface SessionStore {
    fun create(userId: UUID): Session

    fun find(id: String): Session?

    fun invalidate(id: String)
}

@Component
class RedisSessionStore(
    private val redis: StringRedisTemplate,
    private val mapper: ObjectMapper,
    private val clock: Clock,
) : SessionStore {
    private val rng = SecureRandom()

    override fun create(userId: UUID): Session {
        val raw = ByteArray(SESSION_ID_BYTES).also(rng::nextBytes)
        val id = ENCODER.encodeToString(raw)
        val now = clock.instant()
        val data = SessionData(userId = userId.toString(), createdAt = now.toString(), lastSeenAt = now.toString())
        redis.opsForValue().set(key(id), mapper.writeValueAsString(data), TTL)
        return Session(id, userId, now, now)
    }

    override fun find(id: String): Session? {
        val raw = redis.opsForValue().get(key(id)) ?: return null
        val data = mapper.readValue(raw, SessionData::class.java)
        val now = clock.instant()
        // Slide the TTL and update lastSeenAt on every successful lookup.
        val refreshed = data.copy(lastSeenAt = now.toString())
        redis.opsForValue().set(key(id), mapper.writeValueAsString(refreshed), TTL)
        return Session(
            id = id,
            userId = UUID.fromString(refreshed.userId),
            createdAt = java.time.Instant.parse(refreshed.createdAt),
            lastSeenAt = now,
        )
    }

    override fun invalidate(id: String) {
        redis.delete(key(id))
    }

    private fun key(id: String) = "$KEY_PREFIX$id"

    /** Wire-format snapshot persisted in Redis. Public so Jackson can deserialize. */
    data class SessionData
        @JsonCreator
        constructor(
            @JsonProperty("userId") val userId: String,
            @JsonProperty("createdAt") val createdAt: String,
            @JsonProperty("lastSeenAt") val lastSeenAt: String,
        )

    companion object {
        const val KEY_PREFIX = "kliniq:session:"
        private const val SESSION_ID_BYTES = 32
        private val TTL: Duration = Duration.ofDays(30)
        private val ENCODER = Base64.getUrlEncoder().withoutPadding()
    }
}
