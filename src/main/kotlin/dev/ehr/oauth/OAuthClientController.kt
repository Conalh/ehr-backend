package dev.ehr.oauth

import dev.ehr.identity.OAuthClient
import dev.ehr.identity.OAuthClientId
import dev.ehr.identity.OAuthClientType
import dev.ehr.security.SecurityPrincipal
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
import org.springframework.web.server.ResponseStatusException
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
            principal = securityPrincipal(authentication),
            clientIdentifier = request.clientIdentifier,
            displayName = request.displayName,
            clientType = request.clientType ?: OAuthClientType.PUBLIC,
            grantedScopes = request.grantedScopes ?: "",
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
            principal = securityPrincipal(authentication),
            clientId = OAuthClientId(clientId),
        ).toResponse()

    @GetMapping
    fun list(authentication: Authentication): OAuthClientListResponse =
        OAuthClientListResponse(
            clients = oauthClientService.list(securityPrincipal(authentication)).map { it.toResponse() },
        )

    @PostMapping("/{clientId}/revoke")
    fun revoke(
        authentication: Authentication,
        @PathVariable clientId: UUID,
    ): OAuthClientResponse =
        oauthClientService.revoke(
            principal = securityPrincipal(authentication),
            clientId = OAuthClientId(clientId),
        ).toResponse()

    private fun securityPrincipal(authentication: Authentication): SecurityPrincipal =
        authentication.principal as? SecurityPrincipal
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Security principal is not available")
}

data class RegisterOAuthClientRequest(
    @field:NotBlank
    val clientIdentifier: String,
    @field:NotBlank
    val displayName: String,
    val clientType: OAuthClientType? = null,
    val grantedScopes: String? = null,
)

data class OAuthClientResponse(
    val id: String,
    val organizationId: String?,
    val clientIdentifier: String,
    val displayName: String,
    val status: String,
    val clientType: String,
    val grantedScopes: String,
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
        clientSecret = clientSecret,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
