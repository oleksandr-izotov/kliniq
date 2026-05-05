package com.kliniq.usecase.booking

import com.kliniq.domain.booking.Booking
import com.kliniq.persistence.booking.BookingFilter
import com.kliniq.persistence.booking.BookingRepository
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Read-only access to bookings. No auditing — listing isn't a state change.
 */
@Service
class ListBookingsUseCase(
    private val bookings: BookingRepository,
) {
    fun list(filter: BookingFilter): List<Booking> = bookings.findFiltered(filter)

    fun get(id: UUID): Booking? = bookings.findById(id)
}
