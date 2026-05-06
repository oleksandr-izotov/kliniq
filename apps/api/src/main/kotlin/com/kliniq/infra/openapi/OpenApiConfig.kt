package com.kliniq.infra.openapi

import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.security.SecurityRequirement
import io.swagger.v3.oas.models.security.SecurityScheme
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Top-level metadata for the OpenAPI 3.1 spec served at /v3/api-docs.
 *
 * The session is carried by the `__Host-kliniq_session` cookie set by the
 * /login endpoint, so we declare a single cookie-based security scheme
 * and apply it as the default. The auth endpoints that don't require it
 * (register / login / verify / etc.) override their per-operation
 * security to an empty list via `@SecurityRequirements` — but for V1
 * we keep the spec lenient and let the SPA's typed wrappers be the
 * source of truth on which calls expect a session.
 */
@Configuration
class OpenApiConfig {
    @Bean
    fun kliniqOpenApi(): OpenAPI =
        OpenAPI()
            .info(
                Info()
                    .title("Kliniq API")
                    .version("v1")
                    .description(
                        "Operating-room scheduling backend for Kliniq. " +
                            "Session-based auth via the __Host-kliniq_session cookie; " +
                            "every state-changing call also requires the X-XSRF-TOKEN " +
                            "header echoing the XSRF-TOKEN cookie (double-submit pattern).",
                    ),
            ).addSecurityItem(SecurityRequirement().addList("kliniqSession"))
            .components(
                Components()
                    .addSecuritySchemes(
                        "kliniqSession",
                        SecurityScheme()
                            .type(SecurityScheme.Type.APIKEY)
                            .`in`(SecurityScheme.In.COOKIE)
                            .name("__Host-kliniq_session"),
                    ),
            )
}
