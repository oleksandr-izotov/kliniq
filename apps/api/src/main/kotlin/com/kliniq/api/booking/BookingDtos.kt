package com.kliniq.api.booking

import com.kliniq.domain.booking.Booking
import com.kliniq.domain.booking.BookingStatus
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Wire shape for a booking. Exposes `patientRef` because all V1 callers are
 * authenticated staff; the V2 customer portal will need a redacted variant.
 */
data class BookingDto(
    val id: UUID,
    val operatingRoomId: UUID,
    val surgeonId: UUID,
    val createdById: UUID,
    val startsAt: OffsetDateTime,
    val endsAt: OffsetDateTime,
    val opType: String,
    val patientRef: String,
    val status: BookingStatus,
    val notes: String?,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime,
) {
    companion object {
        fun of(b: Booking): BookingDto =
            BookingDto(
                id = b.id,
                operatingRoomId = b.operatingRoomId,
                surgeonId = b.surgeonId,
                createdById = b.createdById,
                startsAt = b.startsAt,
                endsAt = b.endsAt,
                opType = b.opType,
                patientRef = b.patientRef,
                status = b.status,
                notes = b.notes,
                createdAt = b.createdAt,
                updatedAt = b.updatedAt,
            )
    }
}

data class CreateBookingRequest(
    @field:NotNull val operatingRoomId: UUID,
    @field:NotNull val surgeonId: UUID,
    @field:NotNull val startsAt: OffsetDateTime,
    @field:NotNull val endsAt: OffsetDateTime,
    @field:NotBlank
    @field:Size(min = 1, max = MAX_OP_TYPE)
    val opType: String,
    @field:NotBlank
    @field:Size(min = 1, max = MAX_PATIENT_REF)
    @field:Pattern(regexp = "^P-[0-9]{4}-[0-9]{3,}$")
    val patientRef: String,
    @field:Size(max = MAX_NOTES)
    val notes: String? = null,
) {
    companion object {
        const val MAX_OP_TYPE = 200
        const val MAX_PATIENT_REF = 50
        const val MAX_NOTES = 2_000
    }
}

/**
 * Returned alongside a 409 BOOKING_CONFLICT to point the SPA at the
 * specific row that's blocking the slot. Null when the EXCLUDE
 * constraint races us — see CreateBookingUseCase docs.
 */
data class BookingConflictDetail(
    val code: String = "BOOKING_CONFLICT",
    val message: String,
    val occludingBookingId: UUID?,
)
