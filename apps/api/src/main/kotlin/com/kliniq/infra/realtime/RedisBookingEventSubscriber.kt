package com.kliniq.infra.realtime

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.data.redis.connection.Message
import org.springframework.data.redis.connection.MessageListener
import org.springframework.stereotype.Component

/**
 * Receives [BookingEvent]s from Redis pub/sub and hands them to the
 * [LocalEmitterRegistry], which broadcasts to whatever SSE clients are
 * currently connected to *this* JVM. Wired into a
 * `RedisMessageListenerContainer` by [com.kliniq.config.RealtimeConfig].
 *
 * On a deserialization failure we log and drop — events are best-effort,
 * and a bad payload means our wire format diverged from a peer's, which
 * is a config bug rather than something to retry.
 */
@Component
class RedisBookingEventSubscriber(
    private val mapper: ObjectMapper,
    private val registry: LocalEmitterRegistry,
) : MessageListener {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun onMessage(
        message: Message,
        pattern: ByteArray?,
    ) {
        val body = String(message.body, Charsets.UTF_8)
        val event =
            try {
                mapper.readValue(body, BookingEvent::class.java)
            } catch (e: JsonProcessingException) {
                log.warn("booking event deserialize failed (length={}): {}", body.length, e.message)
                return
            }
        registry.broadcast(event)
    }
}
