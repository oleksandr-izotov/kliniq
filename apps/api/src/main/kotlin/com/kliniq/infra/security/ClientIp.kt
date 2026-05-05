package com.kliniq.infra.security

import jakarta.servlet.http.HttpServletRequest

/**
 * Resolve the client IP for rate-limiting and abuse-detection purposes.
 * Honors `X-Forwarded-For` (first hop wins) when present so we get the
 * real remote once we deploy behind a reverse proxy. Falls back to the
 * direct socket peer in dev (mkcert + direct connection).
 *
 * Returns "unknown" only as a last resort; a literal placeholder beats
 * a NullPointerException and rate-limit buckets keyed by "unknown" all
 * collapse together, which is the conservative behaviour.
 */
fun HttpServletRequest.clientIp(): String =
    getHeader("X-Forwarded-For")
        ?.split(",")
        ?.firstOrNull()
        ?.trim()
        .takeUnless { it.isNullOrBlank() }
        ?: remoteAddr
        ?: UNKNOWN_CLIENT_IP

private const val UNKNOWN_CLIENT_IP = "unknown"
