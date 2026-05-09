package com.kliniq.api.admin

import com.kliniq.persistence.audit.AuditEventFilter
import com.kliniq.usecase.audit.ListAuditEventsUseCase
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Read-only admin window into the audit log. SecurityConfig's
 * `hasRole("ADMIN")` matcher gates the whole admin route group;
 * nothing else stands between this controller and the request.
 *
 * No PATCH/POST/DELETE endpoints exist on this resource and never will:
 * audit history is append-only by definition, and the V2 DB triggers
 * forbid UPDATE/DELETE on the underlying table as belt-and-braces.
 */
@RestController
@RequestMapping("/api/v1/admin/audit")
class AuditController(
    private val listEvents: ListAuditEventsUseCase,
) {
    @GetMapping
    fun list(
        @Valid @ModelAttribute query: AuditEventListQuery,
    ): AuditEventPageDto {
        val filter =
            AuditEventFilter(
                entityType = query.entityType,
                actorUserId = query.actorUserId,
                action = query.action,
                from = query.from,
                to = query.to,
            )
        val page = listEvents.list(filter, query.page, query.pageSize)
        return AuditEventPageDto(
            items = page.items.map(AuditEventDto::of),
            page = page.page,
            pageSize = page.pageSize,
            total = page.total,
        )
    }
}
