package dev.ehr.oauth

import dev.ehr.identity.OAuthClient
import dev.ehr.identity.OAuthClientId
import dev.ehr.identity.OAuthClientRepository
import dev.ehr.identity.OAuthClientType
import dev.ehr.security.AccessAuthorizer
import dev.ehr.security.AuditEventService
import dev.ehr.security.AuditOperation
import dev.ehr.security.PolicyOperation
import dev.ehr.security.PolicyResourceType
import dev.ehr.security.SecurityPrincipal
import dev.ehr.security.tenantScope
import dev.ehr.security.SecurityScope
import dev.ehr.security.SmartScopeCompatibility
import dev.ehr.security.SmartScope
import org.springframework.dao.DuplicateKeyException
import org.springframework.http.HttpStatus
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.server.ResponseStatusException
import java.net.URI
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
        val WHITESPACE = Regex("\\s+")
        val IPV4_LOOPBACK = Regex("""127(?:\.(?:25[0-5]|2[0-4]\d|1?\d?\d)){3}""")
        const val MAX_REDIRECT_URIS = 10
        const val MAX_REDIRECT_URI_LENGTH = 2048
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
        if (!SmartScopeCompatibility.areAllowedForClientType(SecurityScope.parse(normalizedScopes), clientType)) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "SMART scope context is not allowed for this client type",
            )
        }
        val normalizedRedirectUris = normalizeRedirectUris(redirectUris)

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
                auditEventService.recordSuccessfulAccess(
                    decision = decision,
                    operation = AuditOperation.CREATE,
                    resourceId = client.id.value,
                )
                RegisteredOAuthClient(client, clientSecret)
            }!!
        } catch (exception: DuplicateKeyException) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "Client identifier already exists")
        }
    }

    private fun normalizeRedirectUris(raw: String): String {
        val values = raw.trim().split(WHITESPACE).filter { it.isNotBlank() }
        if (values.size > MAX_REDIRECT_URIS) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Too many redirect URIs")
        }
        if (values.distinct().size != values.size) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Redirect URIs must be unique")
        }
        values.forEach(::validateRedirectUri)
        return values.joinToString(" ")
    }

    private fun validateRedirectUri(value: String) {
        if (value.length > MAX_REDIRECT_URI_LENGTH) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Redirect URI is too long")
        }
        val uri = runCatching { URI(value) }.getOrNull()
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Redirect URI is malformed")
        val scheme = uri.scheme?.lowercase()
        val host = uri.host ?: throw ResponseStatusException(
            HttpStatus.BAD_REQUEST,
            "Redirect URIs must include a host",
        )
        if (uri.fragment != null || uri.userInfo != null) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Redirect URIs must not include fragments or user info",
            )
        }
        when (scheme) {
            "https" -> Unit
            "http" -> {
                if (!isLoopbackHost(host)) {
                    throw ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "HTTP redirect URIs are allowed only for loopback hosts",
                    )
                }
            }
            else -> throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Redirect URIs must use https, except loopback http redirects for local clients",
            )
        }
    }

    private fun isLoopbackHost(host: String): Boolean {
        val normalized = host.lowercase()
        return normalized == "localhost" ||
            normalized == "::1" ||
            IPV4_LOOPBACK.matches(normalized)
    }

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
            auditEventService.recordFailedAccess(
                decision = decision,
                operation = AuditOperation.READ,
                resourceId = clientId.value,
            )
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Client not found")
        }

        auditEventService.recordSuccessfulAccess(
            decision = decision,
            operation = AuditOperation.READ,
            resourceId = client.id.value,
        )
        return client
    }

    fun list(principal: SecurityPrincipal): List<OAuthClient> {
        val decision = authorize(principal, PolicyOperation.READ, "Not authorized to list clients")

        val clients = oauthClientRepository.findByOrganization(principal.tenantScope())
        auditEventService.recordSuccessfulAccess(
            decision = decision,
            operation = AuditOperation.SEARCH,
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
            auditEventService.recordFailedAccess(
                decision = decision,
                operation = AuditOperation.UPDATE,
                resourceId = clientId.value,
            )
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Client not found")
        }

        return transactionTemplate.execute {
            val revoked = oauthClientRepository.revoke(scope, clientId)
                ?: throw ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Client is already revoked")
            auditEventService.recordSuccessfulAccess(
                decision = decision,
                operation = AuditOperation.UPDATE,
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
