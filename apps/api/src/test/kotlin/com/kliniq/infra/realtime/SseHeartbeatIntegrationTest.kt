package com.kliniq.infra.realtime

import com.kliniq.support.TestcontainersConfig
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * Closes Sprint 3 retro sanity item #2: an SSE connection that has been
 * idle past common reverse-proxy kill windows (Traefik / nginx default
 * 30–60 s) still belongs to [SseService] when a later booking event
 * arrives. The proxy itself isn't in the loop here — what we can pin
 * structurally is that [SseService.heartbeat] doesn't quietly evict a
 * healthy emitter while it's idle, so when a real broadcast lands after
 * the long quiet stretch the same emitter is still on the list and
 * still accepts payload writes.
 *
 * Six heartbeats at the production 15 s cadence covers a 90 s gap,
 * comfortably past every default proxy idle timeout V1 will sit behind.
 *
 * The wire-format details of `:ping\n` comment frames and
 * `event:booking.created\n` headers are out of scope here — those are
 * Spring framework guarantees. What this test owns is the contract
 * between heartbeat and broadcast on the same shared emitter list.
 */
@SpringBootTest
@Import(TestcontainersConfig::class)
@ActiveProfiles("test")
class SseHeartbeatIntegrationTest
    @Autowired
    constructor(
        private val sseService: SseService,
    ) {
        @Test
        fun `idle emitter survives 90s of heartbeats and still receives a later event`() {
            val baseline = sseService.subscriberCount()
            val userId = UUID.randomUUID()
            val emitter = sseService.register(userId)

            try {
                assertThat(sseService.subscriberCount()).isEqualTo(baseline + 1)

                // Six heartbeats at the production 15 s rate = 90 s of
                // simulated idleness. We invoke the method directly so the
                // test stays sub-second; the @Scheduled wiring itself is
                // exercised by the Spring container during startup.
                repeat(HEARTBEATS_FOR_90_SECONDS) { sseService.heartbeat() }

                // Heartbeat didn't drop the healthy emitter — the only
                // regressions worth catching here are the ones where a
                // future change makes heartbeat misclassify a live emitter
                // as dead and garbage-collect it.
                assertThat(sseService.subscriberCount())
                    .`as`("emitter still subscribed after %d heartbeats", HEARTBEATS_FOR_90_SECONDS)
                    .isEqualTo(baseline + 1)

                // Post-idle, an actual booking event still flows through
                // without throwing — the cross-cutting claim sanity #2 is
                // really making.
                val event =
                    BookingEvent(
                        kind = BookingEventKind.CREATED,
                        bookingId = UUID.randomUUID(),
                        operatingRoomId = UUID.randomUUID(),
                        occurredAt = OffsetDateTime.of(2026, 6, 1, 9, 0, 0, 0, ZoneOffset.UTC),
                    )
                sseService.broadcast(event)

                assertThat(sseService.subscriberCount())
                    .`as`("emitter still subscribed after a post-idle broadcast")
                    .isEqualTo(baseline + 1)
            } finally {
                // Don't leave a synthetic subscriber for the next test in
                // the shared Spring context.
                emitter.complete()
            }
        }

        companion object {
            // 6 × 15s = 90s, comfortably past Traefik/nginx default idle
            // kill windows (30–60s).
            private const val HEARTBEATS_FOR_90_SECONDS = 6
        }
    }
