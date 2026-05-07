package com.kliniq.api.realtime

import com.kliniq.infra.realtime.SseService
import com.kliniq.infra.security.KliniqAuthentication
import org.springframework.http.MediaType
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.util.UUID

/**
 * Server-sent events endpoint. Authenticated `STAFF`+ users open one
 * EventSource per browser tab; the SPA's schedule and side-panel
 * subscribe and refresh on `booking.*` events. Every connection runs
 * over the same `__Host-kliniq_session` cookie as the rest of the API,
 * so there's no separate auth dance.
 */
@RestController
@RequestMapping("/api/v1/events")
class EventsController(
    private val sseService: SseService,
) {
    @GetMapping(produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun subscribe(): SseEmitter = sseService.register(currentUserId())

    private fun currentUserId(): UUID =
        (SecurityContextHolder.getContext().authentication as? KliniqAuthentication)
            ?.user
            ?.id
            ?: error("Authenticated endpoint reached without KliniqAuthentication")
}
