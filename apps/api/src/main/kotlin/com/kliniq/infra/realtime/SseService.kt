package com.kliniq.infra.realtime

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Holds the live SSE emitters for this JVM and fans booking events out to
 * them. The Redis subscriber calls [broadcast] on every message it pulls
 * off the channel; the controller calls [register] to mint an emitter for
 * a freshly opened EventSource.
 *
 * In V1 every connected staff sees every booking event — there's no
 * per-user filtering, since `STAFF`+ already has API-level read access to
 * all bookings via `GET /bookings/{id}`. The userId on each subscription
 * is metadata for diagnostics and future filtering.
 *
 * Heartbeat: a `: ping` comment line every 15 seconds keeps idle
 * connections alive past common reverse-proxy timeouts (Traefik, nginx
 * default to 30–60 s on idle TCP). The same loop is the natural place
 * to garbage-collect emitters whose underlying transport is gone — a
 * dead `send()` raises `IOException`, we remove the entry, the next
 * round skips it.
 */
@Component
class SseService(
    private val mapper: ObjectMapper,
) : LocalEmitterRegistry {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * One entry per live EventSource. CopyOnWriteArrayList lets [broadcast]
     * iterate without locking while [register] / cleanup callbacks mutate
     * the list — writes are infrequent (open/close), reads are per-event.
     */
    private val subscriptions = CopyOnWriteArrayList<Subscription>()

    fun register(userId: UUID): SseEmitter {
        val emitter = SseEmitter(EMITTER_TIMEOUT_MS)
        val subscription = Subscription(userId, emitter)
        subscriptions.add(subscription)

        emitter.onCompletion { subscriptions.remove(subscription) }
        emitter.onTimeout {
            subscriptions.remove(subscription)
            emitter.complete()
        }
        emitter.onError {
            subscriptions.remove(subscription)
            emitter.complete()
        }
        log.debug("sse: registered emitter for user={} (subscribers={})", userId, subscriptions.size)
        return emitter
    }

    override fun broadcast(event: BookingEvent) {
        if (subscriptions.isEmpty()) return
        val json = mapper.writeValueAsString(event)
        val message =
            SseEmitter
                .event()
                .name(event.kind.wireName())
                .data(json)
        val dead = mutableListOf<Subscription>()
        for (sub in subscriptions) {
            try {
                sub.emitter.send(message)
            } catch (e: IOException) {
                // Client gone or transport dropped. Remove and let the
                // emitter complete itself; the onCompletion callback may
                // also fire but is idempotent against the list.
                dead.add(sub)
                log.debug("sse: send failed for user={}, dropping: {}", sub.userId, e.message)
            } catch (e: IllegalStateException) {
                // SseEmitter throws this when already completed/timed out.
                // Treat the same as a dead transport.
                dead.add(sub)
                log.debug("sse: emitter completed for user={}, dropping: {}", sub.userId, e.message)
            }
        }
        if (dead.isNotEmpty()) subscriptions.removeAll(dead)
    }

    /**
     * Heartbeat. Sends a comment line — invisible to the application
     * layer but enough to reset the proxy's idle timer. Same dead-emitter
     * cleanup as broadcast.
     */
    @Scheduled(fixedRate = HEARTBEAT_INTERVAL_MS)
    fun heartbeat() {
        if (subscriptions.isEmpty()) return
        val ping = SseEmitter.event().comment("ping")
        val dead = mutableListOf<Subscription>()
        for (sub in subscriptions) {
            try {
                sub.emitter.send(ping)
            } catch (e: IOException) {
                dead.add(sub)
                log.debug("sse: heartbeat dropped subscriber for user={}: {}", sub.userId, e.message)
            } catch (e: IllegalStateException) {
                dead.add(sub)
                log.debug("sse: heartbeat hit completed emitter for user={}: {}", sub.userId, e.message)
            }
        }
        if (dead.isNotEmpty()) subscriptions.removeAll(dead)
    }

    /** Diagnostics + tests. */
    fun subscriberCount(): Int = subscriptions.size

    private data class Subscription(
        val userId: UUID,
        val emitter: SseEmitter,
    )

    companion object {
        // 30 minutes — long enough that EventSource reconnects are rare,
        // short enough that a leaked emitter doesn't pin memory forever.
        // Native EventSource auto-reconnect papers over the timeout so
        // the user-facing experience is uninterrupted.
        private const val EMITTER_TIMEOUT_MS: Long = 30 * 60 * 1000

        // 15 s — comfortably under the 30–60 s idle kill-window of common
        // reverse proxies, while not flooding the wire.
        private const val HEARTBEAT_INTERVAL_MS: Long = 15_000
    }
}
