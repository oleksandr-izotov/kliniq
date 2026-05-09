package com.kliniq.usecase.booking

import com.kliniq.domain.booking.Booking
import com.kliniq.domain.booking.BookingStatus
import com.kliniq.infra.audit.AuditEntry
import com.kliniq.infra.audit.AuditWriter
import com.kliniq.infra.realtime.BookingChangedEvent
import com.kliniq.infra.realtime.BookingEvent
import com.kliniq.infra.realtime.BookingEventKind
import com.kliniq.persistence.booking.BookingRepository
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Single use case driving the booking lifecycle: cancel / start / complete.
 * The valid transitions live on [BookingStatus.canTransitionTo]; this class
 * is the only caller of `transitionStatus` on the repository, so the FSM
 * has exactly one enforcement site.
 *
 * Audit action is derived from the target status:
 *   IN_PROGRESS → "booking.started"
 *   COMPLETED   → "booking.completed"
 *   CANCELLED   → "booking.cancelled"  (carries optional reason in metadata)
 *
 * The repo's atomic compare-and-set on status is what guarantees we don't
 * race against a concurrent transition (e.g. two managers cancelling the
 * same booking simultaneously) — the second call sees the new status, the
 * CAS misses, and we surface IllegalTransition.
 */
@Service
class TransitionBookingUseCase(
    private val bookings: BookingRepository,
    private val auditWriter: AuditWriter,
    private val applicationEventPublisher: ApplicationEventPublisher,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    @Suppress("ReturnCount")
    fun transition(
        actorUserId: UUID,
        id: UUID,
        target: BookingStatus,
        reason: String? = null,
    ): Result {
        val current = bookings.findById(id) ?: return Result.NotFound
        if (!current.status.canTransitionTo(target)) {
            return Result.IllegalTransition(from = current.status, to = target)
        }

        val updated =
            bookings.transitionStatus(id, expected = current.status, target = target)
                // CAS missed — another writer transitioned between our read and write.
                ?: return Result.IllegalTransition(from = current.status, to = target)

        val action =
            when (target) {
                BookingStatus.IN_PROGRESS -> "booking.started"
                BookingStatus.COMPLETED -> "booking.completed"
                BookingStatus.CANCELLED -> "booking.cancelled"
                BookingStatus.SCHEDULED ->
                    error("SCHEDULED is the initial state; cannot be transitioned to")
            }
        val metadata =
            buildMap<String, Any?> {
                put("from", current.status.name)
                put("to", target.name)
                if (target == BookingStatus.CANCELLED && !reason.isNullOrBlank()) {
                    put("reason", reason.trim())
                }
            }
        auditWriter.record(
            AuditEntry(
                action = action,
                entityType = "booking",
                entityId = id,
                actorUserId = actorUserId,
                metadata = metadata,
            ),
        )
        log.info("{}: id={} from={} to={}", action, id, current.status, target)
        // AFTER_COMMIT-bound publish: rolled-back transitions emit nothing.
        applicationEventPublisher.publishEvent(
            BookingChangedEvent(
                BookingEvent(
                    kind =
                        when (target) {
                            BookingStatus.IN_PROGRESS -> BookingEventKind.STARTED
                            BookingStatus.COMPLETED -> BookingEventKind.COMPLETED
                            BookingStatus.CANCELLED -> BookingEventKind.CANCELLED
                            BookingStatus.SCHEDULED ->
                                error("SCHEDULED is the initial state; cannot be transitioned to")
                        },
                    bookingId = updated.id,
                    operatingRoomId = updated.operatingRoomId,
                    occurredAt = updated.updatedAt,
                ),
            ),
        )
        return Result.Success(updated)
    }

    sealed interface Result {
        data class Success(
            val booking: Booking,
        ) : Result

        data object NotFound : Result

        data class IllegalTransition(
            val from: BookingStatus,
            val to: BookingStatus,
        ) : Result
    }
}
