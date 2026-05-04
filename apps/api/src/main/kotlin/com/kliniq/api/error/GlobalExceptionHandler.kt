package com.kliniq.api.error

import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
class GlobalExceptionHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun onValidation(e: MethodArgumentNotValidException): ResponseEntity<ApiErrorResponse> {
        val fieldErrors =
            e.bindingResult.fieldErrors.map {
                ApiErrorResponse.FieldError(
                    field = it.field,
                    message = it.defaultMessage ?: "invalid value",
                )
            }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
            ApiErrorResponse(
                code = "VALIDATION_ERROR",
                message = "One or more fields failed validation.",
                fieldErrors = fieldErrors,
            ),
        )
    }

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun onMalformedBody(): ResponseEntity<ApiErrorResponse> =
        ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
            ApiErrorResponse(
                code = "MALFORMED_REQUEST",
                message = "Request body is missing or not valid JSON.",
            ),
        )

    /**
     * Catch-all so internal errors never leak stack traces to the client.
     * Sentry / OpenTelemetry will pick the original exception up via the
     * logging.
     */
    @ExceptionHandler(Exception::class)
    fun onUnexpected(e: Exception): ResponseEntity<ApiErrorResponse> {
        log.error("Unhandled exception in controller", e)
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
            ApiErrorResponse(
                code = "INTERNAL_ERROR",
                message = "Something went wrong. Please try again.",
            ),
        )
    }
}
