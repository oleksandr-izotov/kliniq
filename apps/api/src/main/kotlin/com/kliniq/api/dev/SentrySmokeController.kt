package com.kliniq.api.dev

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

// Deliberate-throw endpoint used once to verify the Sentry pipeline lands
// events on the dashboard with stack trace + request context attached.
// Gated behind `app.sentry.smoke-enabled=true` so the route only exists
// when Coolify is told to expose it — flip the env var off (or delete the
// file) once the smoke is green. Kept as a separate file so removal is a
// one-line delete with no surrounding surgery.
@RestController
@RequestMapping("/api/v1/dev")
@ConditionalOnProperty(name = ["app.sentry.smoke-enabled"], havingValue = "true")
class SentrySmokeController {
    @GetMapping("/sentry-smoke")
    fun throwForSentry(): ResponseEntity<Nothing> = throw IllegalStateException(SMOKE_MESSAGE)

    private companion object {
        const val SMOKE_MESSAGE = "Sentry smoke test — deliberate throw from SentrySmokeController."
    }
}
