package com.kliniq.usecase.admin

import com.kliniq.domain.user.User
import com.kliniq.persistence.user.UserListFilter
import com.kliniq.persistence.user.UserRepository
import org.springframework.stereotype.Service

/**
 * Paginated, filtered user listing for the admin UI. Returns the current
 * page plus the unbounded total so the SPA can render the right
 * "showing N of M" footer without an extra round-trip.
 */
@Service
class ListUsersUseCase(
    private val users: UserRepository,
) {
    data class Page(
        val items: List<User>,
        val page: Int,
        val pageSize: Int,
        val total: Int,
    )

    fun list(
        filter: UserListFilter,
        page: Int,
        pageSize: Int,
    ): Page {
        val safePage = page.coerceAtLeast(0)
        val safeSize = pageSize.coerceIn(MIN_PAGE_SIZE, MAX_PAGE_SIZE)
        return Page(
            items = users.listAdminUsers(filter, safePage, safeSize),
            page = safePage,
            pageSize = safeSize,
            total = users.countAdminUsers(filter),
        )
    }

    companion object {
        const val DEFAULT_PAGE_SIZE = 50
        private const val MIN_PAGE_SIZE = 1
        private const val MAX_PAGE_SIZE = 200
    }
}
