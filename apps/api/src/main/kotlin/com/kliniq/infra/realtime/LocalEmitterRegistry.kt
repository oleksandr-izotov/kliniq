package com.kliniq.infra.realtime

/**
 * Where the Redis subscriber hands events off to send to connected
 * browsers. The real implementation is [SseService], which holds one
 * `SseEmitter` per open EventSource and fans out to all of them.
 *
 * Kept as an interface so test code can mock the seam between the Redis
 * subscriber and the SSE machinery without dragging in the full SSE
 * lifecycle.
 */
interface LocalEmitterRegistry {
    fun broadcast(event: BookingEvent)
}
