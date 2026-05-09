package com.kliniq.infra.realtime

import com.kliniq.support.TestcontainersConfig
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.never
import org.mockito.kotlin.timeout
import org.mockito.kotlin.verify
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * Closes Sprint 3 retro sanity item #1: "no booking event ever reaches
 * Redis from a transaction that didn't commit."
 *
 * The contract is structural: use cases publish a [BookingChangedEvent]
 * inside their `@Transactional` boundary, and
 * [BookingEventPublisher.onBookingChanged] catches it on
 * `@TransactionalEventListener(AFTER_COMMIT)`. A rolled-back transaction
 * never fires the listener, never hits Redis, and therefore never lands
 * on [SseService.broadcast]. This test pins that behaviour against
 * future refactors that might swap the publish path back to a direct
 * Redis call from inside the transaction.
 */
@SpringBootTest
@Import(TestcontainersConfig::class)
@ActiveProfiles("test")
class BookingPublishAfterCommitTest
    @Autowired
    constructor(
        private val applicationEventPublisher: ApplicationEventPublisher,
        transactionManager: PlatformTransactionManager,
    ) {
        private val tx = TransactionTemplate(transactionManager)

        // Spy keeps SseService wired into EventsController; broadcast is a
        // no-op without connected SSE clients, so verifying the spy gives
        // us the assertion we want without the real fan-out machinery.
        @MockitoSpyBean
        private lateinit var registry: SseService

        @BeforeEach
        fun reset() {
            Mockito.reset(registry)
        }

        @Test
        fun `event published in rolled-back transaction never reaches SseService`() {
            val event = sampleEvent()

            tx.execute { status ->
                applicationEventPublisher.publishEvent(BookingChangedEvent(event))
                status.setRollbackOnly()
            }

            // The publish chain crosses a thread boundary on the subscriber
            // side. Sleep long enough that any stray delivery would have
            // landed before we assert never().
            Thread.sleep(GRACE_PERIOD_MS)
            verify(registry, never()).broadcast(any())
        }

        @Test
        fun `event published in committed transaction reaches SseService`() {
            val event = sampleEvent()

            tx.execute {
                applicationEventPublisher.publishEvent(BookingChangedEvent(event))
            }

            // Async hop: AFTER_COMMIT listener → Redis → subscriber thread
            // → broadcast. timeout() lets us wait up to 2s for that round
            // trip, matching the existing pub/sub integration test.
            verify(registry, timeout(BROADCAST_TIMEOUT_MS)).broadcast(event)
        }

        private fun sampleEvent(): BookingEvent =
            BookingEvent(
                kind = BookingEventKind.CREATED,
                bookingId = UUID.randomUUID(),
                operatingRoomId = UUID.randomUUID(),
                occurredAt = OffsetDateTime.of(2026, 6, 1, 9, 0, 0, 0, ZoneOffset.UTC),
            )

        companion object {
            private const val GRACE_PERIOD_MS = 750L
            private const val BROADCAST_TIMEOUT_MS = 2_000L
        }
    }
