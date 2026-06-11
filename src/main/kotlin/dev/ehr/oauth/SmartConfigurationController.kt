package dev.ehr.oauth

import com.fasterxml.jackson.annotation.JsonProperty
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.support.ServletUriComponentsBuilder

@RestController
class SmartConfigurationController {
    @GetMapping("/.well-known/smart-configuration")
    fun smartConfiguration(): SmartConfigurationResponse {
        val base = ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString()
        return SmartConfigurationResponse(
            authorizationEndpoint = "$base/oauth/authorize",
            tokenEndpoint = "$base/oauth/token",
            revocationEndpoint = "$base/oauth/revoke",
            grantTypesSupported = listOf("authorization_code", "refresh_token", "client_credentials"),
            codeChallengeMethodsSupported = listOf("S256"),
            scopesSupported = listOf(
                "openid",
                "fhirUser",
                "launch/patient",
                "patient/*.read",
                "patient/*.write",
                "patient/*.cruds",
                "user/*.read",
                "user/*.write",
                "user/*.cruds",
                "system/*.read",
                "system/*.write",
                "system/*.cruds",
            ),
            capabilities = listOf(
                "client-confidential-symmetric",
                "client-public",
                "sso-openid-connect",
                "launch-standalone",
                "context-standalone-patient",
                "permission-user",
                "permission-patient",
                "permission-v1",
                "permission-v2",
            ),
        )
    }
}

// SMART discovery uses snake_case member names.
data class SmartConfigurationResponse(
    @get:JsonProperty("authorization_endpoint")
    val authorizationEndpoint: String,
    @get:JsonProperty("token_endpoint")
    val tokenEndpoint: String,
    @get:JsonProperty("revocation_endpoint")
    val revocationEndpoint: String,
    @get:JsonProperty("grant_types_supported")
    val grantTypesSupported: List<String>,
    @get:JsonProperty("code_challenge_methods_supported")
    val codeChallengeMethodsSupported: List<String>,
    @get:JsonProperty("scopes_supported")
    val scopesSupported: List<String>,
    @get:JsonProperty("capabilities")
    val capabilities: List<String>,
)
