package com.kliniq.api.or

import com.kliniq.domain.or.OperatingRoom
import com.kliniq.domain.or.OperatingRoomStatus
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.OffsetDateTime
import java.util.UUID

data class OperatingRoomDto(
    val id: UUID,
    val code: String,
    val name: String,
    val status: OperatingRoomStatus,
    val notes: String?,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime,
) {
    companion object {
        fun of(or: OperatingRoom): OperatingRoomDto =
            OperatingRoomDto(
                id = or.id,
                code = or.code,
                name = or.name,
                status = or.status,
                notes = or.notes,
                createdAt = or.createdAt,
                updatedAt = or.updatedAt,
            )
    }
}

data class CreateOperatingRoomRequest(
    @field:NotBlank
    @field:Size(min = 1, max = MAX_CODE)
    val code: String,
    @field:NotBlank
    @field:Size(min = 1, max = MAX_NAME)
    val name: String,
    @field:Size(max = MAX_NOTES)
    val notes: String? = null,
) {
    companion object {
        const val MAX_CODE = 20
        const val MAX_NAME = 100
        const val MAX_NOTES = 2_000
    }
}

/**
 * Partial update: every field is optional. `notes = ""` clears the
 * column to NULL; `notes = null` leaves it as is.
 */
data class UpdateOperatingRoomRequest(
    @field:Size(min = 1, max = CreateOperatingRoomRequest.MAX_NAME)
    val name: String? = null,
    @field:Size(max = CreateOperatingRoomRequest.MAX_NOTES)
    val notes: String? = null,
    val status: OperatingRoomStatus? = null,
)
