package dev.ehr.fhir

import dev.ehr.terminology.CodeableConcept
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Bounded FHIR search parameter parsing: the standard token and date forms
 * the US Core SHALL searches use — nothing more. Malformed input is a 400,
 * never a guess.
 */
data class FhirTokenParam(
    val system: String?,
    val code: String,
) {
    fun matches(
        candidateSystem: String?,
        candidateCode: String?,
    ): Boolean =
        code == candidateCode && (system == null || system == candidateSystem)

    fun matchesConcept(concept: CodeableConcept): Boolean =
        concept.codings.any { matches(it.system, it.code) }

    companion object {
        /** `system|code` or bare `code` (bare matches any system). */
        fun parse(raw: String): FhirTokenParam {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Token search parameter must not be blank")
            }
            val separator = trimmed.lastIndexOf('|')
            return if (separator < 0) {
                FhirTokenParam(system = null, code = trimmed)
            } else {
                val code = trimmed.substring(separator + 1)
                if (code.isEmpty()) {
                    throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Token search parameter is missing a code")
                }
                FhirTokenParam(system = trimmed.substring(0, separator).takeIf { it.isNotEmpty() }, code = code)
            }
        }
    }
}

/** The single supported reverse include (US Core / g10's who-did-what). */
object ProvenanceRevInclude {
    const val VALUE = "Provenance:target"

    /** null → not requested; the supported value → requested; else 400. */
    fun isRequested(raw: String?): Boolean {
        if (raw == null) {
            return false
        }
        if (raw == VALUE) {
            return true
        }
        throw ResponseStatusException(
            HttpStatus.BAD_REQUEST,
            "Only _revinclude=Provenance:target is supported",
        )
    }
}

/** Half-open instant range; null bounds are unbounded. */
data class FhirDateRange(
    val from: Instant?,
    val toExclusive: Instant?,
) {
    fun contains(instant: Instant): Boolean =
        (from == null || !instant.isBefore(from)) &&
            (toExclusive == null || instant.isBefore(toExclusive))

    companion object {
        private val PREFIXES = setOf("eq", "ge", "gt", "le", "lt")

        /**
         * `[prefix]value` where value is a day (`2026-06-01`, treated as the
         * UTC day) or an instant. Multiple date parameters AND together at
         * the call site, the standard FHIR range idiom.
         */
        fun parse(raw: String): FhirDateRange {
            val trimmed = raw.trim()
            val prefix = trimmed.take(2).takeIf { it in PREFIXES } ?: "eq"
            val value = if (trimmed.take(2) in PREFIXES) trimmed.drop(2) else trimmed

            val (start, end) = if (value.length == 10) {
                runCatching {
                    val day = LocalDate.parse(value)
                    day.atStartOfDay(ZoneOffset.UTC).toInstant() to
                        day.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant()
                }.getOrElse { invalid(raw) }
            } else {
                runCatching {
                    val instant = Instant.parse(value)
                    instant to instant.plusMillis(1)
                }.getOrElse { invalid(raw) }
            }

            return when (prefix) {
                "eq" -> FhirDateRange(from = start, toExclusive = end)
                "ge" -> FhirDateRange(from = start, toExclusive = null)
                "gt" -> FhirDateRange(from = end, toExclusive = null)
                "le" -> FhirDateRange(from = null, toExclusive = end)
                else -> FhirDateRange(from = null, toExclusive = start)
            }
        }

        private fun invalid(raw: String): Nothing =
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Date search parameter must be [eq|ge|gt|le|lt]YYYY-MM-DD or an instant: $raw",
            )
    }
}
