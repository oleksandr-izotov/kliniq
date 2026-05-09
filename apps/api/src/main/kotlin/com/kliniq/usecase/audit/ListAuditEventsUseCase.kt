package com.kliniq.usecase.audit

import com.kliniq.domain.audit.AuditEvent
import com.kliniq.persistence.audit.AuditEventFilter
import com.kliniq.persistence.audit.AuditEventRepository
import org.springframework.stereotype.Service

/**
 * Paginated, filtered read of `audit_events`. Same shape as the admin
 * user listing — return the page plus an unbounded total so the SPA
 * renders "showing N of M" without a second call.
 */
@Service
class ListAuditEventsUseCase(
    private val events: AuditEventRepository,
) {
    data class Page(
        val items: List<AuditEvent>,
        val page: Int,
        val pageSize: Int,
        val total: Int,
    )

    fun list(
        filter: AuditEventFilter,
        page: Int,
        pageSize: Int,
    ): Page {
        val safePage = page.coerceAtLeast(0)
        val safeSize = pageSize.coerceIn(MIN_PAGE_SIZE, MAX_PAGE_SIZE)
        return Page(
            items = events.list(filter, safePage, safeSize),
            page = safePage,
            pageSize = safeSize,
            total = events.count(filter),
        )
    }

    companion object {
        const val DEFAULT_PAGE_SIZE = 50
        private const val MIN_PAGE_SIZE = 1
        private const val MAX_PAGE_SIZE = 200
    }
}
