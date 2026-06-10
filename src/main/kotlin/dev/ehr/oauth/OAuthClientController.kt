package dev.ehr.oauth

import dev.ehr.identity.OAuthClient
import dev.ehr.identity.OAuthClientId
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
    ): OAuthClientResponse =
        oauthClientService.register(
            principal = securityPrincipal(authentication),
            clientIdentifier = request.clientIdentifier,
            displayName = request.displayName,
        ).toResponse()

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
)

data class OAuthClientResponse(
    val id: String,
    val organizationId: String?,
    val clientIdentifier: String,
    val displayName: String,
    val status: String,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class OAuthClientListResponse(
    val clients: List<OAuthClientResponse>,
)

private fun OAuthClient.toResponse(): OAuthClientResponse =
    OAuthClientResponse(
        id = id.value.toString(),
        organizationId = organizationId?.value?.toString(),
        clientIdentifier = clientIdentifier,
        displayName = displayName,
        status = status.dbValue,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
