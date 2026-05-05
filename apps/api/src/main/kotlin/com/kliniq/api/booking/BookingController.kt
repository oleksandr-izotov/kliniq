package com.kliniq.api.booking

import com.kliniq.api.error.ApiErrorResponse
import com.kliniq.domain.booking.BookingPatch
import com.kliniq.domain.booking.BookingStatus
import com.kliniq.infra.security.KliniqAuthentication
import com.kliniq.persistence.booking.BookingFilter
import com.kliniq.usecase.booking.CreateBookingUseCase
import com.kliniq.usecase.booking.ListBookingsUseCase
import com.kliniq.usecase.booking.TransitionBookingUseCase
import com.kliniq.usecase.booking.UpdateBookingUseCase
import jakarta.validation.Valid
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.OffsetDateTime
import java.util.UUID

@RestController
@RequestMapping("/api/v1/bookings")
@Suppress("LongParameterList") // controller orchestrates many use cases via DI
class BookingController(
    private val createUseCase: CreateBookingUseCase,
    private val updateUseCase: UpdateBookingUseCase,
    private val listUseCase: ListBookingsUseCase,
    private val transitionUseCase: TransitionBookingUseCase,
) {
    // ---- READ ----------------------------------------------------------

    @GetMapping
    fun list(
        @RequestParam(required = false) operatingRoomId: UUID?,
        @RequestParam(required = false) surgeonId: UUID?,
        @RequestParam(required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
        from: OffsetDateTime?,
        @RequestParam(required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
        to: OffsetDateTime?,
        @RequestParam(required = false) status: BookingStatus?,
    ): List<BookingDto> =
        listUseCase
            .list(
                BookingFilter(
                    operatingRoomId = operatingRoomId,
                    surgeonId = surgeonId,
                    fromInclusive = from,
                    toExclusive = to,
                    status = status,
                ),
            ).map(BookingDto::of)

    @GetMapping("/{id}")
    fun get(
        @PathVariable id: UUID,
    ): ResponseEntity<*> =
        listUseCase.get(id)?.let { ResponseEntity.ok(BookingDto.of(it)) }
            ?: ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                ApiErrorResponse(code = "NOT_FOUND", message = "Booking not found."),
            )

    // ---- CREATE --------------------------------------------------------

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
                badRequest("VALIDATION_ERROR", result.reason)
            CreateBookingUseCase.Result.SurgeonNotFound -> badRequest("SURGEON_NOT_FOUND", "Surgeon not found.")
            CreateBookingUseCase.Result.NotASurgeon ->
                badRequest("NOT_A_SURGEON", "Selected user is not flagged as a surgeon.")
            CreateBookingUseCase.Result.SurgeonInactive ->
                badRequest("SURGEON_INACTIVE", "Surgeon's account is disabled.")
            CreateBookingUseCase.Result.OperatingRoomNotFound ->
                badRequest("OR_NOT_FOUND", "Operating room not found.")
            CreateBookingUseCase.Result.OperatingRoomInactive ->
                badRequest("OR_INACTIVE", "Operating room is in maintenance or retired.")
            CreateBookingUseCase.Result.OutsideWorkingHours ->
                badRequest("OUTSIDE_WORKING_HOURS", "Booking falls outside the clinic's working hours.")
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

    // ---- UPDATE --------------------------------------------------------

    @PatchMapping("/{id}")
    @Suppress("CyclomaticComplexMethod", "LongMethod") // one branch per Result variant
    fun update(
        @PathVariable id: UUID,
        @Valid @RequestBody request: UpdateBookingRequest,
    ): ResponseEntity<*> {
        val patch =
            BookingPatch(
                operatingRoomId = request.operatingRoomId,
                surgeonId = request.surgeonId,
                startsAt = request.startsAt,
                endsAt = request.endsAt,
                opType = request.opType?.trim(),
                notes = request.notes,
            )
        return when (val result = updateUseCase.update(actorUserId = currentUserId(), id = id, patch = patch)) {
            is UpdateBookingUseCase.Result.Success ->
                ResponseEntity.ok(BookingDto.of(result.booking))
            UpdateBookingUseCase.Result.NotFound ->
                ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                    ApiErrorResponse(code = "NOT_FOUND", message = "Booking not found."),
                )
            is UpdateBookingUseCase.Result.TerminalState ->
                ResponseEntity.status(HttpStatus.CONFLICT).body(
                    ApiErrorResponse(
                        code = "BOOKING_TERMINAL",
                        message = "Booking is ${result.status.name.lowercase()} and can't be edited.",
                    ),
                )
            is UpdateBookingUseCase.Result.InvalidInput -> badRequest("VALIDATION_ERROR", result.reason)
            UpdateBookingUseCase.Result.SurgeonNotFound -> badRequest("SURGEON_NOT_FOUND", "Surgeon not found.")
            UpdateBookingUseCase.Result.NotASurgeon ->
                badRequest("NOT_A_SURGEON", "Selected user is not flagged as a surgeon.")
            UpdateBookingUseCase.Result.SurgeonInactive ->
                badRequest("SURGEON_INACTIVE", "Surgeon's account is disabled.")
            UpdateBookingUseCase.Result.OperatingRoomNotFound ->
                badRequest("OR_NOT_FOUND", "Operating room not found.")
            UpdateBookingUseCase.Result.OperatingRoomInactive ->
                badRequest("OR_INACTIVE", "Operating room is in maintenance or retired.")
            UpdateBookingUseCase.Result.OutsideWorkingHours ->
                badRequest("OUTSIDE_WORKING_HOURS", "Booking falls outside the clinic's working hours.")
            is UpdateBookingUseCase.Result.BookingConflict ->
                ResponseEntity.status(HttpStatus.CONFLICT).body(
                    BookingConflictDetail(
                        message =
                            "An active booking already overlaps this slot on the selected operating room.",
                        occludingBookingId = result.occludingBookingId,
                    ),
                )
        }
    }

    // ---- LIFECYCLE -----------------------------------------------------

    @PostMapping("/{id}/cancel")
    fun cancel(
        @PathVariable id: UUID,
        @Valid @RequestBody(required = false) request: CancelBookingRequest?,
    ): ResponseEntity<*> = transition(id, BookingStatus.CANCELLED, request?.reason)

    @PostMapping("/{id}/start")
    fun start(
        @PathVariable id: UUID,
    ): ResponseEntity<*> = transition(id, BookingStatus.IN_PROGRESS, reason = null)

    @PostMapping("/{id}/complete")
    fun complete(
        @PathVariable id: UUID,
    ): ResponseEntity<*> = transition(id, BookingStatus.COMPLETED, reason = null)

    private fun transition(
        id: UUID,
        target: BookingStatus,
        reason: String?,
    ): ResponseEntity<*> =
        when (val result = transitionUseCase.transition(currentUserId(), id, target, reason)) {
            is TransitionBookingUseCase.Result.Success -> ResponseEntity.ok(BookingDto.of(result.booking))
            TransitionBookingUseCase.Result.NotFound ->
                ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                    ApiErrorResponse(code = "NOT_FOUND", message = "Booking not found."),
                )
            is TransitionBookingUseCase.Result.IllegalTransition ->
                ResponseEntity.status(HttpStatus.CONFLICT).body(
                    ApiErrorResponse(
                        code = "ILLEGAL_TRANSITION",
                        message =
                            "Cannot transition booking from ${result.from.name.lowercase()} to " +
                                "${result.to.name.lowercase()}.",
                    ),
                )
        }

    private fun badRequest(
        code: String,
        message: String,
    ) = ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiErrorResponse(code = code, message = message))

    private fun currentUserId(): UUID =
        (SecurityContextHolder.getContext().authentication as? KliniqAuthentication)
            ?.user
            ?.id
            ?: error("Authenticated endpoint reached without KliniqAuthentication")
}
