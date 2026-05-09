package com.kliniq.infra.realtime

/**
 * Spring application event used as the in-process bridge between a
 * booking use case and the Redis publish path. Use cases publish this
 * synchronously inside their `@Transactional` boundary;
 * [BookingEventPublisher.onBookingChanged] catches it on the
 * `AFTER_COMMIT` phase and only then calls
 * [BookingEventPublisher.publish].
 *
 * Why the indirection? A direct Redis publish from inside the use
 * case fires whether or not the surrounding transaction commits — a
 * rollback after a successful publish leaves connected browsers
 * thinking the booking changed when it didn't. Routing through
 * `ApplicationEventPublisher` + `@TransactionalEventListener` makes
 * the publish phase-bound: rolled-back work emits no SSE event.
 */
data class BookingChangedEvent(
    val event: BookingEvent,
)
