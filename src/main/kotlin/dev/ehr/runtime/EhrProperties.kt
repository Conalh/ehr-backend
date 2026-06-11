package dev.ehr.runtime

import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated

/**
 * Validated, typed configuration for the security-critical settings.
 * Misconfiguration fails at startup, not at first request.
 */
@Validated
@ConfigurationProperties(prefix = "ehr")
data class EhrProperties(
    @field:Valid
    val security: Security = Security(),
    @field:Valid
    val export: Export = Export(),
    @field:Valid
    val rateLimit: RateLimit = RateLimit(),
    @field:Valid
    val compartment: Compartment = Compartment(),
) {
    data class Security(
        @field:NotBlank
        @field:Size(min = 32, message = "ehr.security.dev-jwt-secret must be at least 32 bytes for HS256")
        val devJwtSecret: String = "",
    )

    data class Export(
        @field:NotBlank
        val storageDir: String = System.getProperty("java.io.tmpdir") + "/ehr-exports",
    )

    data class RateLimit(
        @field:Min(1)
        val requestsPerMinute: Int = 1000,
    )

    data class Compartment(
        // Encounter-derived care-team memberships auto-end this many days
        // after the sustaining encounter completes (design decision: 30,
        // configurable).
        @field:Min(1)
        val encounterDerivedExpiryDays: Int = 30,
    )
}
