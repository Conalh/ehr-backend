package dev.ehr.authz

import dev.ehr.identity.OAuthClient
import dev.ehr.identity.OAuthClientRepository
import dev.ehr.identity.OAuthClientStatus
import dev.ehr.identity.OAuthClientType
import org.springframework.security.oauth2.core.AuthorizationGrantType
import org.springframework.security.oauth2.core.ClientAuthenticationMethod
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * Adapts oauth_clients to the authorization server. Clients are managed only
 * through the audited registration API. Grants follow the client type:
 * system clients get client_credentials; confidential and public clients get
 * the authorization-code flow (PKCE mandatory for public) with rotating
 * refresh tokens — but only when they registered a redirect URI; a client
 * without one fails closed here.
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
        return when (client.clientType) {
            OAuthClientType.SYSTEM -> systemClient(client)
            OAuthClientType.CONFIDENTIAL -> userClient(client, confidential = true)
            OAuthClientType.PUBLIC -> userClient(client, confidential = false)
        }
    }

    private fun systemClient(client: OAuthClient): RegisteredClient? {
        val secretHash = client.secretHash ?: return null
        val builder = RegisteredClient.withId(client.id.value.toString())
            .clientId(client.clientIdentifier)
            .clientSecret(secretHash)
            .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
            .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
            .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
            .tokenSettings(
                TokenSettings.builder()
                    .accessTokenTimeToLive(ACCESS_TOKEN_TTL)
                    .build(),
            )
        scopesOf(client).forEach { builder.scope(it) }
        return builder.build()
    }

    private fun userClient(
        client: OAuthClient,
        confidential: Boolean,
    ): RegisteredClient? {
        val redirectUris = client.redirectUris.split(" ").filter { it.isNotBlank() }
        if (redirectUris.isEmpty()) {
            return null
        }
        if (confidential && client.secretHash == null) {
            return null
        }

        val builder = RegisteredClient.withId(client.id.value.toString())
            .clientId(client.clientIdentifier)
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
            .clientSettings(
                ClientSettings.builder()
                    // First-party synthetic apps: no consent screen (recorded
                    // AS2 decision 4). PKCE is mandatory for public clients.
                    .requireAuthorizationConsent(false)
                    .requireProofKey(!confidential)
                    .build(),
            )
            .tokenSettings(
                TokenSettings.builder()
                    .accessTokenTimeToLive(ACCESS_TOKEN_TTL)
                    .refreshTokenTimeToLive(REFRESH_TOKEN_TTL)
                    // Rotation: each use issues a new refresh token and
                    // invalidates the old one.
                    .reuseRefreshTokens(false)
                    .build(),
            )
        if (confidential) {
            builder
                .clientSecret(client.secretHash)
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
        } else {
            builder.clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
        }
        redirectUris.forEach { builder.redirectUri(it) }
        scopesOf(client).forEach { builder.scope(it) }
        return builder.build()
    }

    private fun scopesOf(client: OAuthClient): List<String> =
        client.grantedScopes.split(" ").filter { it.isNotBlank() }

    private companion object {
        // Design decision 4: short-lived access, 90-day rotating refresh.
        val ACCESS_TOKEN_TTL: Duration = Duration.ofMinutes(5)
        val REFRESH_TOKEN_TTL: Duration = Duration.ofDays(90)
    }
}
