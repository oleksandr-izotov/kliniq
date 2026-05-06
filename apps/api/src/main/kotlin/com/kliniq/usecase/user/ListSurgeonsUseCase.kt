package com.kliniq.usecase.user

import com.kliniq.domain.user.User
import com.kliniq.persistence.user.UserRepository
import org.springframework.stereotype.Service

/**
 * Read-only listing for the booking modal's surgeon picker. Filters out
 * disabled accounts and non-surgeons at the repository layer so callers
 * never have to remember the invariants.
 */
@Service
class ListSurgeonsUseCase(
    private val users: UserRepository,
) {
    fun list(): List<User> = users.findActiveSurgeons()
}
