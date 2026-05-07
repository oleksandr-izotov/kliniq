package com.kliniq.infra.realtime

import com.kliniq.support.TestcontainersConfig
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.timeout
import org.mockito.kotlin.verify
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * Verifies the publisher → Redis → subscriber pipe end-to-end. The
 * subscriber's downstream collaborator (LocalEmitterRegistry) is mocked so
 * we can assert exact payloads and ordering without dragging the SSE
 * machinery in — that lands on Day 30.
 */
@SpringBootTest
@Import(TestcontainersConfig::class)
@ActiveProfiles("test")
class BookingEventPubSubIntegrationTest
    @Autowired
    constructor(
        private val publisher: BookingEventPublisher,
        private val redis: StringRedisTemplate,
    ) {
        // Spy the real SseService so EventsController's typed dependency
        // still resolves; SseService.broadcast is a no-op when no
        // EventSource clients are connected, so the spy lets us verify
        // the call without touching real SSE machinery.
        @MockitoSpyBean
        private lateinit var registry: SseService

        @Test
        fun `events flow publisher to subscriber in order`() {
            val orId = UUID.randomUUID()
            val now = OffsetDateTime.of(2026, 6, 1, 9, 0, 0, 0, ZoneOffset.UTC)
            val events =
                listOf(
                    BookingEvent(BookingEventKind.CREATED, UUID.randomUUID(), orId, now),
                    BookingEvent(BookingEventKind.STARTED, UUID.randomUUID(), orId, now.plusMinutes(1)),
                    BookingEvent(BookingEventKind.COMPLETED, UUID.randomUUID(), orId, now.plusMinutes(2)),
                )

            events.forEach(publisher::publish)

            val captor = argumentCaptor<BookingEvent>()
            // Pub/sub is async; the listener container delivers on its own
            // thread. timeout() lets us wait up to 2s for all three events
            // to land before failing.
            verify(registry, timeout(SUBSCRIBE_TIMEOUT_MS).times(events.size))
                .broadcast(captor.capture())

            assertThat(captor.allValues).containsExactlyElementsOf(events)
        }

        @Test
        fun `malformed payloads are dropped without crashing the listener`() {
            // Send raw garbage straight through the channel, bypassing the
            // publisher's serializer. The subscriber must swallow this and
            // remain ready to deliver the next good event.
            redis.convertAndSend(BookingEventPublisher.BOOKING_EVENTS_CHANNEL, "{not json")
            redis.convertAndSend(BookingEventPublisher.BOOKING_EVENTS_CHANNEL, "")

            val good = BookingEvent(BookingEventKind.UPDATED, UUID.randomUUID(), UUID.randomUUID(), now())
            publisher.publish(good)

            verify(registry, timeout(SUBSCRIBE_TIMEOUT_MS)).broadcast(good)
        }

        private fun now() = OffsetDateTime.of(2026, 6, 1, 12, 0, 0, 0, ZoneOffset.UTC)

        companion object {
            private const val SUBSCRIBE_TIMEOUT_MS = 2_000L
        }
    }
