package com.kliniq.infra.realtime

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

/**
 * Single producer for booking change events. Publishes JSON to Redis
 * pub/sub on [BOOKING_EVENTS_CHANNEL] ; every Spring instance has a
 * subscriber that fans out to its locally connected SSE emitters, so
 * a horizontal-scale deploy still gets one logical broadcast.
 *
 * Use cases never call [publish] directly. They publish a
 * [BookingChangedEvent] through Spring's `ApplicationEventPublisher`
 * inside their `@Transactional` boundary; [onBookingChanged] catches
 * it on the `AFTER_COMMIT` phase and only then calls Redis. Rolled-back
 * transactions emit nothing — the Sprint 3 retro's "no event before
 * commit" sanity item turned into a structural guarantee.
 */
@Component
class BookingEventPublisher(
    private val redis: StringRedisTemplate,
    private val mapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun onBookingChanged(envelope: BookingChangedEvent) {
        publish(envelope.event)
    }

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
