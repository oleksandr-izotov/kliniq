package com.kliniq.infra.realtime

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component

/**
 * Single producer for booking change events. Publishes JSON to Redis
 * pub/sub on [BOOKING_EVENTS_CHANNEL] ; every Spring instance has a
 * subscriber that fans out to its locally connected SSE emitters, so
 * a horizontal-scale deploy still gets one logical broadcast.
 *
 * Use cases call [publish] directly after their audit row is written.
 * Until the booking use cases gain a single `@Transactional` boundary,
 * we don't gain anything by re-routing through `@TransactionalEventListener` ;
 * the audit row itself doubles as our "did the write actually happen?"
 * signal because both the booking insert and the audit insert auto-commit
 * on success.
 */
@Component
class BookingEventPublisher(
    private val redis: StringRedisTemplate,
    private val mapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Suppress("TooGenericExceptionCaught") // see comment in catch block
    fun publish(event: BookingEvent) {
        try {
            redis.convertAndSend(BOOKING_EVENTS_CHANNEL, mapper.writeValueAsString(event))
        } catch (e: Exception) {
            // Real-time delivery is best-effort: the booking is already
            // committed, the audit row is already written. A subscriber
            // miss only means a connected browser sees the change on its
            // next poll instead of instantly. We genuinely want to swallow
            // any runtime failure — Redis bounce, serializer hiccup,
            // whatever — rather than fail the user-facing call.
            log.warn("booking event publish failed for {}: {}", event.bookingId, e.message)
        }
    }

    companion object {
        const val BOOKING_EVENTS_CHANNEL: String = "kliniq:booking-events"
    }
}
