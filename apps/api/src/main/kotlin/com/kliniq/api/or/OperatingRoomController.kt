package com.kliniq.api.or

import com.kliniq.api.error.ApiErrorResponse
import com.kliniq.domain.or.OperatingRoomPatch
import com.kliniq.infra.security.KliniqAuthentication
import com.kliniq.usecase.or.CreateOperatingRoomUseCase
import com.kliniq.usecase.or.ListOperatingRoomsUseCase
import com.kliniq.usecase.or.UpdateOperatingRoomUseCase
import jakarta.validation.Valid
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
import java.util.UUID

@RestController
@RequestMapping("/api/v1/operating-rooms")
class OperatingRoomController(
    private val createUseCase: CreateOperatingRoomUseCase,
    private val updateUseCase: UpdateOperatingRoomUseCase,
    private val listUseCase: ListOperatingRoomsUseCase,
) {
    /** All authenticated users can read the list. */
    @GetMapping
    fun list(
        @RequestParam(name = "includeRetired", defaultValue = "false") includeRetired: Boolean,
    ): List<OperatingRoomDto> = listUseCase.list(includeRetired).map(OperatingRoomDto::of)

    @GetMapping("/{id}")
    fun get(
        @PathVariable id: UUID,
    ): ResponseEntity<*> =
        listUseCase.get(id)?.let { ResponseEntity.ok(OperatingRoomDto.of(it)) }
            ?: ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                ApiErrorResponse(code = "NOT_FOUND", message = "Operating room not found."),
            )

    /** MANAGER+ only — gated in [com.kliniq.config.SecurityConfig]. */
    @PostMapping
    fun create(
        @Valid @RequestBody request: CreateOperatingRoomRequest,
    ): ResponseEntity<*> =
        when (
            val result =
                createUseCase.create(
                    actorUserId = currentUserId(),
                    cmd =
                        CreateOperatingRoomUseCase.Command(
                            code = request.code,
                            name = request.name,
                            notes = request.notes,
                        ),
                )
        ) {
            is CreateOperatingRoomUseCase.Result.Success ->
                ResponseEntity.status(HttpStatus.CREATED).body(OperatingRoomDto.of(result.operatingRoom))
            CreateOperatingRoomUseCase.Result.DuplicateCode ->
                ResponseEntity.status(HttpStatus.CONFLICT).body(
                    ApiErrorResponse(
                        code = "DUPLICATE_CODE",
                        message = "An operating room with that code already exists.",
                    ),
                )
            is CreateOperatingRoomUseCase.Result.InvalidInput ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                    ApiErrorResponse(code = "VALIDATION_ERROR", message = result.reason),
                )
        }

    @PatchMapping("/{id}")
    fun update(
        @PathVariable id: UUID,
        @Valid @RequestBody request: UpdateOperatingRoomRequest,
    ): ResponseEntity<*> {
        val patch =
            OperatingRoomPatch(
                name = request.name?.trim(),
                // Pass through null vs blank-string distinction so the repo can clear notes.
                notes = request.notes,
                status = request.status,
            )
        return when (val result = updateUseCase.update(actorUserId = currentUserId(), id = id, patch = patch)) {
            is UpdateOperatingRoomUseCase.Result.Success ->
                ResponseEntity.ok(OperatingRoomDto.of(result.operatingRoom))
            UpdateOperatingRoomUseCase.Result.NotFound ->
                ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                    ApiErrorResponse(code = "NOT_FOUND", message = "Operating room not found."),
                )
            is UpdateOperatingRoomUseCase.Result.InvalidInput ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                    ApiErrorResponse(code = "VALIDATION_ERROR", message = result.reason),
                )
        }
    }

    private fun currentUserId(): UUID =
        (SecurityContextHolder.getContext().authentication as? KliniqAuthentication)
            ?.user
            ?.id
            ?: error("Authenticated endpoint reached without KliniqAuthentication")
}
