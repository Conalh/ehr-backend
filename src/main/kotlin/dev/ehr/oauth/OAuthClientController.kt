package dev.ehr.oauth

import dev.ehr.identity.OAuthClient
import dev.ehr.identity.OAuthClientId
import dev.ehr.identity.OAuthClientType
import dev.ehr.security.securityPrincipal
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.http.HttpStatus
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

@RestController
@RequestMapping("/api/v1/oauth-clients")
class OAuthClientController(
    private val oauthClientService: OAuthClientService,
) {
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun register(
        authentication: Authentication,
        @Valid @RequestBody request: RegisterOAuthClientRequest,
    ): OAuthClientResponse {
        val registered = oauthClientService.register(
            principal = authentication.securityPrincipal(),
            clientIdentifier = request.clientIdentifier,
            displayName = request.displayName,
            clientType = request.clientType ?: OAuthClientType.PUBLIC,
            grantedScopes = request.grantedScopes ?: "",
            redirectUris = request.redirectUris ?: "",
        )
        // The plain secret appears in this response and never again.
        return registered.client.toResponse(clientSecret = registered.clientSecret)
    }

    @GetMapping("/{clientId}")
    fun get(
        authentication: Authentication,
        @PathVariable clientId: UUID,
    ): OAuthClientResponse =
        oauthClientService.get(
            principal = authentication.securityPrincipal(),
            clientId = OAuthClientId(clientId),
        ).toResponse()

    @GetMapping
    fun list(authentication: Authentication): OAuthClientListResponse =
        OAuthClientListResponse(
            clients = oauthClientService.list(authentication.securityPrincipal()).map { it.toResponse() },
        )

    @PostMapping("/{clientId}/revoke")
    fun revoke(
        authentication: Authentication,
        @PathVariable clientId: UUID,
    ): OAuthClientResponse =
        oauthClientService.revoke(
            principal = authentication.securityPrincipal(),
            clientId = OAuthClientId(clientId),
        ).toResponse()

}

data class RegisterOAuthClientRequest(
    @field:NotBlank
    val clientIdentifier: String,
    @field:NotBlank
    val displayName: String,
    val clientType: OAuthClientType? = null,
    val grantedScopes: String? = null,
    val redirectUris: String? = null,
)

data class OAuthClientResponse(
    val id: String,
    val organizationId: String?,
    val clientIdentifier: String,
    val displayName: String,
    val status: String,
    val clientType: String,
    val grantedScopes: String,
    val redirectUris: String,
    // Present only in the registration response for confidential/system clients.
    val clientSecret: String? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class OAuthClientListResponse(
    val clients: List<OAuthClientResponse>,
)

private fun OAuthClient.toResponse(clientSecret: String? = null): OAuthClientResponse =
    OAuthClientResponse(
        id = id.value.toString(),
        organizationId = organizationId?.value?.toString(),
        clientIdentifier = clientIdentifier,
        displayName = displayName,
        status = status.dbValue,
        clientType = clientType.dbValue,
        grantedScopes = grantedScopes,
        redirectUris = redirectUris,
        clientSecret = clientSecret,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
