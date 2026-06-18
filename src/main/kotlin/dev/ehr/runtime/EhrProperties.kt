package dev.ehr.runtime

import jakarta.validation.Valid
import jakarta.validation.constraints.AssertTrue
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated
import java.nio.charset.StandardCharsets

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
        val devJwtSecret: String = "",
        // Issuer of tokens minted by the embedded authorization server; the
        // resource-server decoder routes on it.
        @field:NotBlank
        val issuer: String = "http://localhost:8080",
        // Shared password for the authorization server's dev login page —
        // users carry no credentials (synthetic data, no IdP). Loudly
        // dev-only, same fail-at-boot posture as the dev JWT secret.
        @field:NotBlank
        @field:Size(min = 16, message = "ehr.security.dev-login-password must be at least 16 characters")
        val devLoginPassword: String = "",
        val devJwtEnabled: Boolean = false,
    ) {
        @AssertTrue(message = "ehr.security.dev-jwt-secret must be at least 32 bytes when ehr.security.dev-jwt-enabled is true")
        fun isDevJwtSecretValidWhenEnabled(): Boolean =
            !devJwtEnabled ||
                (devJwtSecret.isNotBlank() && devJwtSecret.toByteArray(StandardCharsets.UTF_8).size >= MIN_HS256_KEY_BYTES)

        private companion object {
            const val MIN_HS256_KEY_BYTES = 32
        }
    }

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
