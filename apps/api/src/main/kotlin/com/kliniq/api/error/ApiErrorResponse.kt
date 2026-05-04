package com.kliniq.api.error

/**
 * Standardised error envelope for all 4xx/5xx responses. Matches the contract
 * documented in ARCHITECTURE.md so the frontend can rely on a stable shape.
 */
data class ApiErrorResponse(
    val code: String,
    val message: String,
    val fieldErrors: List<FieldError>? = null,
) {
    data class FieldError(
        val field: String,
        val message: String,
    )
}
