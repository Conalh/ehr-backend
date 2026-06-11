package dev.ehr.oauth

import com.fasterxml.jackson.annotation.JsonProperty
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
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
            grantTypesSupported = listOf("client_credentials"),
            scopesSupported = listOf(
                "openid",
                "fhirUser",
                "user/*.read",
                "user/*.write",
                "user/*.cruds",
                "system/*.read",
                "system/*.write",
                "system/*.cruds",
            ),
            capabilities = listOf(
                "client-confidential-symmetric",
                "permission-user",
                "permission-v1",
                "permission-v2",
            ),
        )
    }

    // The token endpoint is live (embedded authorization server, Slice AS1).
    // The authorize endpoint remains an explicit integration point until AS2:
    // it refuses loudly rather than pretending to run an OAuth flow.
    @GetMapping("/oauth/authorize")
    fun authorizeStub(): ResponseEntity<OAuthStubResponse> =
        ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).body(
            OAuthStubResponse(
                error = "unsupported",
                errorDescription = "Authorization code flows are not supported yet; " +
                    "this endpoint is an integration point for a future authorization server.",
            ),
        )
}

// SMART discovery and OAuth error payloads use snake_case member names.
data class SmartConfigurationResponse(
    @get:JsonProperty("authorization_endpoint")
    val authorizationEndpoint: String,
    @get:JsonProperty("token_endpoint")
    val tokenEndpoint: String,
    @get:JsonProperty("grant_types_supported")
    val grantTypesSupported: List<String>,
    @get:JsonProperty("scopes_supported")
    val scopesSupported: List<String>,
    @get:JsonProperty("capabilities")
    val capabilities: List<String>,
)

data class OAuthStubResponse(
    val error: String,
    @get:JsonProperty("error_description")
    val errorDescription: String,
)
