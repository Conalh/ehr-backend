package dev.ehr.oauth

import dev.ehr.identity.OAuthClient
import dev.ehr.identity.OAuthClientId
import dev.ehr.identity.OAuthClientRepository
import dev.ehr.identity.OAuthClientType
import dev.ehr.security.AccessAuthorizer
import dev.ehr.security.AuditEventService
import dev.ehr.security.AuditOperation
import dev.ehr.security.AuditOutcome
import dev.ehr.security.PolicyOperation
import dev.ehr.security.PolicyResourceType
import dev.ehr.security.SecurityPrincipal
import dev.ehr.security.tenantScope
import dev.ehr.security.SecurityScope
import dev.ehr.security.SmartScope
import org.springframework.dao.DuplicateKeyException
import org.springframework.http.HttpStatus
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.server.ResponseStatusException
import java.security.SecureRandom
import java.util.Base64

/** The plain secret exists only here, in the registration response. */
data class RegisteredOAuthClient(
    val client: OAuthClient,
    val clientSecret: String?,
)

@Service
class OAuthClientService(
    private val accessAuthorizer: AccessAuthorizer,
    private val auditEventService: AuditEventService,
    private val oauthClientRepository: OAuthClientRepository,
    private val passwordEncoder: PasswordEncoder,
    private val transactionTemplate: TransactionTemplate,
) {
    private val secureRandom = SecureRandom()

    private companion object {
        // Non-clinical scopes a client may hold besides SMART resource scopes.
        val OIDC_SCOPES = setOf("openid", "fhirUser", "launch/patient")
    }

    fun register(
        principal: SecurityPrincipal,
        clientIdentifier: String,
        displayName: String,
        clientType: OAuthClientType,
        grantedScopes: String,
        redirectUris: String,
    ): RegisteredOAuthClient {
        val decision = authorize(principal, PolicyOperation.WRITE, "Not authorized to register clients")
        if (clientIdentifier.isBlank() || displayName.isBlank()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Client identifier and display name must not be blank")
        }
        val normalizedScopes = grantedScopes.trim()
        val invalidScope = SecurityScope.parse(normalizedScopes).any { scope ->
            scope.rawValue !in OIDC_SCOPES && SmartScope.parse(scope.rawValue) == null
        }
        if (invalidScope) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Granted scopes must be valid SMART or OIDC scopes")
        }
        // Redirect URIs are optional at registration: a client without one is
        // a directory entry that simply cannot run the authorization-code
        // flow (the registered-client adapter fails it closed).
        val normalizedRedirectUris = redirectUris.trim()
        val redirectUriList = normalizedRedirectUris.split(" ").filter { it.isNotBlank() }
        if (redirectUriList.any { !isAbsoluteHttpUri(it) }) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Redirect URIs must be absolute http(s) URIs")
        }

        val clientSecret = if (clientType == OAuthClientType.PUBLIC) null else generateSecret()
        try {
            return transactionTemplate.execute {
                val client = oauthClientRepository.create(
                    organizationId = principal.organization.organizationId,
                    clientIdentifier = clientIdentifier,
                    displayName = displayName,
                    clientType = clientType,
                    secretHash = clientSecret?.let(passwordEncoder::encode),
                    grantedScopes = normalizedScopes,
                    redirectUris = normalizedRedirectUris,
                )
                auditEventService.recordResourceAccess(
                    decision = decision,
                    operation = AuditOperation.CREATE,
                    outcome = AuditOutcome.SUCCESS,
                    resourceId = client.id.value,
                )
                RegisteredOAuthClient(client, clientSecret)
            }!!
        } catch (exception: DuplicateKeyException) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "Client identifier already exists")
        }
    }

    private fun isAbsoluteHttpUri(value: String): Boolean =
        runCatching {
            val uri = java.net.URI(value)
            (uri.scheme == "http" || uri.scheme == "https") && uri.host != null
        }.getOrDefault(false)

    private fun generateSecret(): String {
        val bytes = ByteArray(32)
        secureRandom.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    fun get(
        principal: SecurityPrincipal,
        clientId: OAuthClientId,
    ): OAuthClient {
        val decision = authorize(principal, PolicyOperation.READ, "Not authorized to read clients", resourceId = clientId.value)

        val client = oauthClientRepository.findById(principal.tenantScope(), clientId)
        if (client == null) {
            auditEventService.recordResourceAccess(
                decision = decision,
                operation = AuditOperation.READ,
                outcome = AuditOutcome.FAILURE,
                resourceId = clientId.value,
            )
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Client not found")
        }

        auditEventService.recordResourceAccess(
            decision = decision,
            operation = AuditOperation.READ,
            outcome = AuditOutcome.SUCCESS,
            resourceId = client.id.value,
        )
        return client
    }

    fun list(principal: SecurityPrincipal): List<OAuthClient> {
        val decision = authorize(principal, PolicyOperation.READ, "Not authorized to list clients")

        val clients = oauthClientRepository.findByOrganization(principal.tenantScope())
        auditEventService.recordResourceAccess(
            decision = decision,
            operation = AuditOperation.SEARCH,
            outcome = AuditOutcome.SUCCESS,
        )
        return clients
    }

    fun revoke(
        principal: SecurityPrincipal,
        clientId: OAuthClientId,
    ): OAuthClient {
        val decision = authorize(principal, PolicyOperation.WRITE, "Not authorized to revoke clients", resourceId = clientId.value)

        val scope = principal.tenantScope()
        val existing = oauthClientRepository.findById(scope, clientId)
        if (existing == null) {
            auditEventService.recordResourceAccess(
                decision = decision,
                operation = AuditOperation.UPDATE,
                outcome = AuditOutcome.FAILURE,
                resourceId = clientId.value,
            )
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Client not found")
        }

        return transactionTemplate.execute {
            val revoked = oauthClientRepository.revoke(scope, clientId)
                ?: throw ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Client is already revoked")
            auditEventService.recordResourceAccess(
                decision = decision,
                operation = AuditOperation.UPDATE,
                outcome = AuditOutcome.SUCCESS,
                resourceId = revoked.id.value,
            )
            revoked
        }!!
    }

    private fun authorize(
        principal: SecurityPrincipal,
        operation: PolicyOperation,
        forbiddenMessage: String,
        resourceId: java.util.UUID? = null,
    ) = accessAuthorizer.authorize(
        principal = principal,
        resourceType = PolicyResourceType.OAUTH_CLIENT,
        operation = operation,
        forbiddenMessage = forbiddenMessage,
        resourceId = resourceId,
    )

}
