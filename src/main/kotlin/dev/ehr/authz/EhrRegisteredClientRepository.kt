package dev.ehr.authz

import dev.ehr.identity.OAuthClientRepository
import dev.ehr.identity.OAuthClientStatus
import dev.ehr.identity.OAuthClientType
import org.springframework.security.oauth2.core.AuthorizationGrantType
import org.springframework.security.oauth2.core.ClientAuthenticationMethod
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * Adapts oauth_clients to the authorization server. Clients are managed only
 * through the audited registration API; only active, secret-bearing clients
 * can authenticate, and only for the client-credentials grant (AS1).
 */
@Component
class EhrRegisteredClientRepository(
    private val oauthClientRepository: OAuthClientRepository,
) : RegisteredClientRepository {
    override fun save(registeredClient: RegisteredClient) {
        throw UnsupportedOperationException("Clients are managed via the registration API")
    }

    override fun findById(id: String): RegisteredClient? = null

    override fun findByClientId(clientId: String): RegisteredClient? {
        val client = oauthClientRepository.findByClientIdentifier(clientId) ?: return null
        if (client.status != OAuthClientStatus.ACTIVE) {
            return null
        }
        if (client.clientType == OAuthClientType.PUBLIC || client.secretHash == null) {
            return null
        }

        val builder = RegisteredClient.withId(client.id.value.toString())
            .clientId(client.clientIdentifier)
            .clientSecret(client.secretHash)
            .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
            .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
            .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
            .tokenSettings(
                TokenSettings.builder()
                    .accessTokenTimeToLive(ACCESS_TOKEN_TTL)
                    .build(),
            )
        client.grantedScopes.split(" ")
            .filter { it.isNotBlank() }
            .forEach { builder.scope(it) }
        return builder.build()
    }

    private companion object {
        // Design decision 4: short-lived access tokens.
        val ACCESS_TOKEN_TTL: Duration = Duration.ofMinutes(5)
    }
}
