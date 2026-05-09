package com.kliniq.infra.realtime

import com.fasterxml.jackson.databind.ObjectMapper
import com.kliniq.support.TestcontainersConfig
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.spy
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.timeout
import org.mockito.kotlin.verify
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.core.task.SyncTaskExecutor
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.listener.PatternTopic
import org.springframework.data.redis.listener.RedisMessageListenerContainer
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.testcontainers.containers.GenericContainer
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * Closes Sprint 3 retro sanity item #4: an event published from one
 * Spring instance reaches the SseService of a different Spring instance
 * via the shared Redis pub/sub channel. This is the structural claim
 * horizontal scale rests on — without it, a booking made on instance A
 * doesn't surface to a browser whose SSE connection landed on instance B,
 * and the V1 deploy can't safely run more than one replica.
 *
 * "Two instances" is constructed as:
 *   - Instance A: the test's main @SpringBootTest context (full app +
 *     spied SseService).
 *   - Instance B: a second set of realtime beans built by hand —
 *     dedicated [LettuceConnectionFactory], dedicated
 *     [RedisMessageListenerContainer], dedicated [SseService] spy. Same
 *     Testcontainer Redis, same channel, otherwise isolated.
 *
 * A second full @SpringBootApplication context would prove the same
 * cross-instance contract but adds ~30 s of bootstrap for no extra
 * coverage. The lean variant tests the wire claim — publish on A,
 * receive on B's [SseService] — in well under a second once the
 * container is warm.
 */
@SpringBootTest
@Import(TestcontainersConfig::class)
@ActiveProfiles("test")
class BookingPubSubTwoInstancesTest
    @Autowired
    constructor(
        private val publisher: BookingEventPublisher,
        private val mapper: ObjectMapper,
        // The Testcontainer is itself a Spring bean (declared in
        // TestcontainersConfig). Reading host/port off it is the
        // canonical handle — `@ServiceConnection` wires the main
        // context's RedisConnectionFactory directly via ConnectionDetails
        // and doesn't surface `spring.data.redis.host` as a resolvable
        // property, so a `@Value`-based read silently grabs the
        // localhost defaults and instance B subscribes to nothing.
        @Qualifier("redis") private val redisContainer: GenericContainer<*>,
    ) {
        @MockitoSpyBean
        private lateinit var instanceASse: SseService

        private lateinit var instanceBConnFactory: LettuceConnectionFactory
        private lateinit var instanceBContainer: RedisMessageListenerContainer
        private lateinit var instanceBSse: SseService

        @BeforeEach
        fun bootInstanceB() {
            instanceBConnFactory =
                LettuceConnectionFactory(redisContainer.host, redisContainer.firstMappedPort).apply {
                    afterPropertiesSet()
                }
            instanceBSse = spy(SseService(mapper))
            val subscriber = RedisBookingEventSubscriber(mapper, instanceBSse)
            instanceBContainer =
                RedisMessageListenerContainer().apply {
                    setConnectionFactory(instanceBConnFactory)
                    // Mirrors RealtimeConfig: synchronous executor preserves
                    // single-channel ordering, which the SPA's "refetch on
                    // event" pattern silently relies on.
                    setTaskExecutor(SyncTaskExecutor())
                    addMessageListener(subscriber, PatternTopic(BookingEventPublisher.BOOKING_EVENTS_CHANNEL))
                    afterPropertiesSet()
                    start()
                }
            // Redis pub/sub has no backfill: publishing before instance B's
            // listener has registered with Redis loses the message silently.
            // Poll isListening — Spring flips it true only after the
            // SUBSCRIBE round-trip completes.
            val deadline = System.currentTimeMillis() + SUBSCRIPTION_READY_TIMEOUT_MS
            while (!instanceBContainer.isListening && System.currentTimeMillis() < deadline) {
                Thread.sleep(SUBSCRIPTION_POLL_MS)
            }
            require(instanceBContainer.isListening) {
                "instance B Redis listener didn't activate within ${SUBSCRIPTION_READY_TIMEOUT_MS}ms"
            }
        }

        @AfterEach
        fun stopInstanceB() {
            instanceBContainer.stop()
            instanceBContainer.destroy()
            instanceBConnFactory.destroy()
        }

        @Test
        fun `event published on instance A reaches instance B's SseService`() {
            val event =
                BookingEvent(
                    kind = BookingEventKind.CREATED,
                    bookingId = UUID.randomUUID(),
                    operatingRoomId = UUID.randomUUID(),
                    occurredAt = OffsetDateTime.of(2026, 6, 1, 9, 0, 0, 0, ZoneOffset.UTC),
                )

            publisher.publish(event)

            // Both instances subscribe to the same channel, so a single
            // publish should land on both — A's spied SseService and B's
            // hand-built one. Asserting both proves the cross-instance
            // contract symmetrically and pins instance A's local fan-out
            // as a side check.
            val captorA = argumentCaptor<BookingEvent>()
            verify(instanceASse, timeout(BROADCAST_TIMEOUT_MS)).broadcast(captorA.capture())
            assertThat(captorA.firstValue).isEqualTo(event)

            val captorB = argumentCaptor<BookingEvent>()
            verify(instanceBSse, timeout(BROADCAST_TIMEOUT_MS)).broadcast(captorB.capture())
            assertThat(captorB.firstValue).isEqualTo(event)
        }

        companion object {
            private const val SUBSCRIPTION_READY_TIMEOUT_MS = 5_000L
            private const val SUBSCRIPTION_POLL_MS = 50L
            private const val BROADCAST_TIMEOUT_MS = 2_000L
        }
    }
