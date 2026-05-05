package com.kliniq.api.booking

import com.kliniq.api.error.ApiErrorResponse
import com.kliniq.infra.security.KliniqAuthentication
import com.kliniq.usecase.booking.CreateBookingUseCase
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/v1/bookings")
class BookingController(
    private val createUseCase: CreateBookingUseCase,
) {
    @PostMapping
    @Suppress("CyclomaticComplexMethod", "LongMethod") // one branch per Result variant
    fun create(
        @Valid @RequestBody request: CreateBookingRequest,
    ): ResponseEntity<*> {
        val cmd =
            CreateBookingUseCase.Command(
                operatingRoomId = request.operatingRoomId,
                surgeonId = request.surgeonId,
                createdById = currentUserId(),
                startsAt = request.startsAt,
                endsAt = request.endsAt,
                opType = request.opType,
                patientRef = request.patientRef,
                notes = request.notes,
            )
        return when (val result = createUseCase.create(cmd)) {
            is CreateBookingUseCase.Result.Success ->
                ResponseEntity.status(HttpStatus.CREATED).body(BookingDto.of(result.booking))
            is CreateBookingUseCase.Result.InvalidInput ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                    ApiErrorResponse(code = "VALIDATION_ERROR", message = result.reason),
                )
            CreateBookingUseCase.Result.SurgeonNotFound ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                    ApiErrorResponse(code = "SURGEON_NOT_FOUND", message = "Surgeon not found."),
                )
            CreateBookingUseCase.Result.NotASurgeon ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                    ApiErrorResponse(
                        code = "NOT_A_SURGEON",
                        message = "Selected user is not flagged as a surgeon.",
                    ),
                )
            CreateBookingUseCase.Result.SurgeonInactive ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                    ApiErrorResponse(code = "SURGEON_INACTIVE", message = "Surgeon's account is disabled."),
                )
            CreateBookingUseCase.Result.OperatingRoomNotFound ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                    ApiErrorResponse(code = "OR_NOT_FOUND", message = "Operating room not found."),
                )
            CreateBookingUseCase.Result.OperatingRoomInactive ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                    ApiErrorResponse(
                        code = "OR_INACTIVE",
                        message = "Operating room is in maintenance or retired.",
                    ),
                )
            CreateBookingUseCase.Result.OutsideWorkingHours ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                    ApiErrorResponse(
                        code = "OUTSIDE_WORKING_HOURS",
                        message = "Booking falls outside the clinic's working hours.",
                    ),
                )
            is CreateBookingUseCase.Result.BookingConflict ->
                ResponseEntity.status(HttpStatus.CONFLICT).body(
                    BookingConflictDetail(
                        message =
                            "An active booking already overlaps this slot on the selected operating room.",
                        occludingBookingId = result.occludingBookingId,
                    ),
                )
        }
    }

    private fun currentUserId(): UUID =
        (SecurityContextHolder.getContext().authentication as? KliniqAuthentication)
            ?.user
            ?.id
            ?: error("Authenticated endpoint reached without KliniqAuthentication")
}
