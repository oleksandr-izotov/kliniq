package com.kliniq.infra.realtime

import java.time.OffsetDateTime
import java.util.UUID

/**
 * Wire shape for a real-time booking notification. Travels publisher →
 * Redis pub/sub → all subscribers (one per Spring instance) → local SSE
 * emitters → connected browsers.
 *
 * Intentionally minimal — recipients re-fetch the full booking via
 * `GET /bookings/{id}` so the event payload doesn't have to grow when
 * the BookingDto schema does, and so role-based field filtering happens
 * at the REST endpoint instead of being duplicated here.
 */
data class BookingEvent(
    val kind: BookingEventKind,
    val bookingId: UUID,
    val operatingRoomId: UUID,
    val occurredAt: OffsetDateTime,
)

enum class BookingEventKind {
    CREATED,
    UPDATED,
    CANCELLED,
    STARTED,
    COMPLETED,
    ;

    /**
     * Lower-case dotted label used as the SSE `event:` field on the wire
     * (e.g. `booking.created`). Matches the action prefix in the audit log.
     */
    fun wireName(): String = "booking.${name.lowercase()}"
}
