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
 * Where browser sessions live. The Redis key is `kliniq:session:{id}`. A
 * companion set `kliniq:user-sessions:{userId}` indexes the active session
 * ids per user so password-reset and admin "logout everywhere" flows can
 * invalidate them in O(n) where n is the user's open device count. TTL is
 * 30 days but slides on each access — every `find` extends the lifetime,
 * so active users stay logged in indefinitely while idle ones are GC'd.
 */
interface SessionStore {
    fun create(userId: UUID): Session

    fun find(id: String): Session?

    fun invalidate(id: String)

    /** Logout-everywhere primitive used by password reset and admin actions. */
    fun invalidateAllForUser(userId: UUID): Int
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
        redis.opsForValue().set(sessionKey(id), mapper.writeValueAsString(data), TTL)
        // Reverse index for user → sessions. Expire on the same TTL so an
        // abandoned set doesn't hang around forever.
        val indexKey = userIndexKey(userId)
        redis.opsForSet().add(indexKey, id)
        redis.expire(indexKey, TTL)
        return Session(id, userId, now, now)
    }

    override fun find(id: String): Session? {
        val raw = redis.opsForValue().get(sessionKey(id)) ?: return null
        val data = mapper.readValue(raw, SessionData::class.java)
        val now = clock.instant()
        // Slide the TTL and update lastSeenAt on every successful lookup.
        val refreshed = data.copy(lastSeenAt = now.toString())
        redis.opsForValue().set(sessionKey(id), mapper.writeValueAsString(refreshed), TTL)
        // Slide the user index TTL too so it doesn't disappear under us.
        redis.expire(userIndexKey(UUID.fromString(refreshed.userId)), TTL)
        return Session(
            id = id,
            userId = UUID.fromString(refreshed.userId),
            createdAt = java.time.Instant.parse(refreshed.createdAt),
            lastSeenAt = now,
        )
    }

    override fun invalidate(id: String) {
        // Read first so we can also pull the id out of the user index.
        val raw = redis.opsForValue().get(sessionKey(id))
        if (raw != null) {
            val data = mapper.readValue(raw, SessionData::class.java)
            redis.opsForSet().remove(userIndexKey(UUID.fromString(data.userId)), id)
        }
        redis.delete(sessionKey(id))
    }

    override fun invalidateAllForUser(userId: UUID): Int {
        val indexKey = userIndexKey(userId)
        val sessionIds = redis.opsForSet().members(indexKey) ?: emptySet()
        if (sessionIds.isEmpty()) return 0
        val keys = sessionIds.map(::sessionKey) + indexKey
        redis.delete(keys)
        return sessionIds.size
    }

    private fun sessionKey(id: String) = "$SESSION_PREFIX$id"

    private fun userIndexKey(userId: UUID) = "$USER_INDEX_PREFIX$userId"

    /** Wire-format snapshot persisted in Redis. Public so Jackson can deserialize. */
    data class SessionData
        @JsonCreator
        constructor(
            @JsonProperty("userId") val userId: String,
            @JsonProperty("createdAt") val createdAt: String,
            @JsonProperty("lastSeenAt") val lastSeenAt: String,
        )

    companion object {
        const val SESSION_PREFIX = "kliniq:session:"
        const val USER_INDEX_PREFIX = "kliniq:user-sessions:"
        private const val SESSION_ID_BYTES = 32
        private val TTL: Duration = Duration.ofDays(30)
        private val ENCODER = Base64.getUrlEncoder().withoutPadding()
    }
}
