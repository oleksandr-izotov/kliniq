package com.kliniq.domain.auth

import java.time.Instant
import java.util.UUID

/**
 * An authenticated browser session. Lives in Redis with sliding TTL — see
 * [com.kliniq.infra.security.RedisSessionStore]. The [id] is opaque (32
 * random bytes, base64url) and is the value of the session cookie sent to
 * the browser; nothing else should be exposed beyond the auth filter.
 */
data class Session(
    val id: String,
    val userId: UUID,
    val createdAt: Instant,
    val lastSeenAt: Instant,
)
