package com.kliniq.config

import com.kliniq.infra.realtime.BookingEventPublisher
import com.kliniq.infra.realtime.RedisBookingEventSubscriber
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.task.SyncTaskExecutor
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.data.redis.listener.PatternTopic
import org.springframework.data.redis.listener.RedisMessageListenerContainer

/**
 * Wires the Redis pub/sub backplane that drives real-time booking
 * notifications. Every Spring instance subscribes to one channel and
 * hands incoming messages to [RedisBookingEventSubscriber], which fans
 * them out to whatever SSE clients are connected here.
 *
 * The container is auto-started by Spring; no explicit lifecycle calls.
 *
 * `taskExecutor` is set to a synchronous executor so listener invocations
 * happen on the subscriber thread in receive order. The default
 * `SimpleAsyncTaskExecutor` spawns one thread per message, which loses
 * the ordering Redis itself guarantees on a single channel — and
 * out-of-order BookingEvents (e.g. STARTED arriving before CREATED)
 * confuse SSE clients in ways the SPA's "refetch on event" pattern
 * doesn't recover from. The fan-out itself is cheap (write to in-memory
 * SseEmitters), so serializing won't bottleneck under any realistic
 * booking volume.
 */
@Configuration
class RealtimeConfig {
    @Bean
    fun redisMessageListenerContainer(
        connectionFactory: RedisConnectionFactory,
        subscriber: RedisBookingEventSubscriber,
    ): RedisMessageListenerContainer =
        RedisMessageListenerContainer().apply {
            setConnectionFactory(connectionFactory)
            setTaskExecutor(SyncTaskExecutor())
            addMessageListener(subscriber, PatternTopic(BookingEventPublisher.BOOKING_EVENTS_CHANNEL))
        }
}
