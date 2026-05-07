package com.kliniq.infra.realtime

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Where the Redis subscriber hands events off to send to connected
 * browsers. Day 30 will replace [LoggingLocalEmitterRegistry] with the
 * real `SseService` that holds one `SseEmitter` per open EventSource.
 *
 * The seam exists today so the publisher → Redis → subscriber pipeline
 * can be wired up and tested ahead of the SSE endpoint itself.
 */
interface LocalEmitterRegistry {
    fun broadcast(event: BookingEvent)
}

/**
 * Default no-op (logging) implementation. Replaced by a real SSE-backed
 * implementation on Day 30 — until then, calls land in the application
 * log so the dev can verify the pipe end-to-end without an SSE client.
 */
@Component
class LoggingLocalEmitterRegistry : LocalEmitterRegistry {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun broadcast(event: BookingEvent) {
        log.info(
            "booking event received locally: kind={} bookingId={} or={}",
            event.kind.wireName(),
            event.bookingId,
            event.operatingRoomId,
        )
    }
}
