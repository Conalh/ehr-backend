package dev.ehr.runtime

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.ErrorResponse
import org.springframework.web.server.ResponseStatusException

/**
 * Safety-net error handler for the API and FHIR surfaces. FHIR controllers
 * catch ResponseStatusException internally and return OperationOutcome
 * themselves; this advice only sees exceptions that escape controllers
 * (uncaught IllegalArgumentException, NullPointerException, validation
 * failures). It produces a consistent JSON envelope for API paths and an
 * OperationOutcome for FHIR paths, never leaking stack traces or internal
 * messages (server.error.include-message and include-stacktrace default to NEVER).
 */
@RestControllerAdvice
class GlobalExceptionHandler(
    private val objectMapper: ObjectMapper,
) {
    @ExceptionHandler(ResponseStatusException::class)
    fun handleResponseStatus(
        exception: ResponseStatusException,
        request: HttpServletRequest,
    ): ResponseEntity<Any> =
        if (request.requestURI.startsWith("/fhir/")) {
            fhirOutcome(
                HttpStatus.valueOf(exception.statusCode.value()),
                issueTypeFor(HttpStatus.valueOf(exception.statusCode.value())),
                exception.reason ?: "Request could not be processed",
            )
        } else {
            jsonEnvelope(
                HttpStatus.valueOf(exception.statusCode.value()),
                exception.reason ?: "Request could not be processed",
            )
        }

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidation(
        exception: MethodArgumentNotValidException,
        request: HttpServletRequest,
    ): ResponseEntity<Any> {
        val message = exception.fieldErrors.joinToString(", ") { "${it.field}: ${it.defaultMessage}" }
        return if (request.requestURI.startsWith("/fhir/")) {
            fhirOutcome(HttpStatus.BAD_REQUEST, "invalid", message)
        } else {
            jsonEnvelope(HttpStatus.BAD_REQUEST, message)
        }
    }

    @ExceptionHandler(Exception::class)
    fun handleUnhandled(
        exception: Exception,
        request: HttpServletRequest,
    ): ResponseEntity<Any> {
        // Let Spring's own MVC exceptions (method not supported, media type
        // not supported, etc.) keep their default status codes.
        if (exception is ErrorResponse) {
            val status = HttpStatus.valueOf(exception.statusCode.value())
            return if (request.requestURI.startsWith("/fhir/")) {
                fhirOutcome(status, issueTypeFor(status), exception.message ?: "Request could not be processed")
            } else {
                jsonEnvelope(status, exception.message ?: "Request could not be processed")
            }
        }
        return if (request.requestURI.startsWith("/fhir/")) {
            fhirOutcome(HttpStatus.INTERNAL_SERVER_ERROR, "processing", "Internal error")
        } else {
            jsonEnvelope(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error")
        }
    }

    private fun jsonEnvelope(status: HttpStatus, message: String): ResponseEntity<Any> =
        ResponseEntity.status(status)
            .contentType(MediaType.APPLICATION_JSON)
            .body(objectMapper.writeValueAsString(mapOf("error" to message)))

    private fun fhirOutcome(status: HttpStatus, issueType: String, diagnostics: String): ResponseEntity<Any> {
        val body = objectMapper.writeValueAsString(
            mapOf(
                "resourceType" to "OperationOutcome",
                "issue" to listOf(
                    mapOf(
                        "severity" to "error",
                        "code" to issueType,
                        "diagnostics" to diagnostics,
                    ),
                ),
            ),
        )
        return ResponseEntity.status(status)
            .contentType(MediaType.parseMediaType("application/fhir+json"))
            .body(body)
    }

    private fun issueTypeFor(status: HttpStatus): String = when (status) {
        HttpStatus.FORBIDDEN -> "forbidden"
        HttpStatus.NOT_FOUND -> "not-found"
        HttpStatus.BAD_REQUEST -> "invalid"
        HttpStatus.CONFLICT -> "conflict"
        else -> "processing"
    }
}
