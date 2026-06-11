package dev.ehr.authz

import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.source.ImmutableJWKSet
import com.nimbusds.jose.jwk.source.JWKSource
import com.nimbusds.jose.proc.SecurityContext
import dev.ehr.identity.OAuthClientRepository
import dev.ehr.runtime.EhrProperties
import dev.ehr.security.JwtClaimNames
import dev.ehr.security.JwtPrincipalAuthenticationConverter
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.Order
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.oauth2.core.AuthorizationGrantType
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configurers.OAuth2AuthorizationServerConfigurer
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings
import org.springframework.security.oauth2.server.authorization.token.DelegatingOAuth2TokenGenerator
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext
import org.springframework.security.oauth2.server.authorization.token.JwtGenerator
import org.springframework.security.oauth2.server.authorization.token.OAuth2AccessTokenGenerator
import org.springframework.security.oauth2.server.authorization.token.OAuth2RefreshTokenGenerator
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenGenerator
import org.springframework.security.web.SecurityFilterChain
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.util.UUID

/**
 * Embedded authorization server (design decision 1B): client-credentials
 * tokens for backend services in AS1. The RSA keypair is generated at
 * startup — tokens die with the process, the documented dev posture.
 */
@Configuration
class AuthorizationServerConfiguration {
    @Bean
    @Order(0)
    fun authorizationServerSecurityFilterChain(http: HttpSecurity): SecurityFilterChain {
        val authorizationServerConfigurer = OAuth2AuthorizationServerConfigurer.authorizationServer()
        http
            .securityMatcher(authorizationServerConfigurer.endpointsMatcher)
            .csrf { it.ignoringRequestMatchers(authorizationServerConfigurer.endpointsMatcher) }
            .with(authorizationServerConfigurer) { }
        return http.build()
    }

    @Bean
    fun authorizationServerSettings(properties: EhrProperties): AuthorizationServerSettings =
        AuthorizationServerSettings.builder()
            .issuer(properties.security.issuer)
            // The paths the SMART discovery document already advertises.
            .tokenEndpoint("/oauth/token")
            .jwkSetEndpoint("/oauth/jwks")
            .build()

    @Bean
    fun jwkSource(): JWKSource<SecurityContext> {
        val keyPair = KeyPairGenerator.getInstance("RSA")
            .apply { initialize(2048) }
            .generateKeyPair()
        val rsaKey = RSAKey.Builder(keyPair.public as RSAPublicKey)
            .privateKey(keyPair.private as RSAPrivateKey)
            .keyID(UUID.randomUUID().toString())
            .build()
        return ImmutableJWKSet(JWKSet(rsaKey))
    }

    @Bean
    fun passwordEncoder(): PasswordEncoder = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8()

    /**
     * Explicit generator pinned to the authorization server's own RS256
     * keys — otherwise the configurer would discover whatever JwtEncoder
     * bean exists in the context (the HS256 dev encoder, in tests).
     */
    @Bean
    fun tokenGenerator(
        jwkSource: JWKSource<SecurityContext>,
        systemTokenCustomizer: OAuth2TokenCustomizer<JwtEncodingContext>,
    ): OAuth2TokenGenerator<*> {
        val jwtGenerator = JwtGenerator(NimbusJwtEncoder(jwkSource))
        jwtGenerator.setJwtCustomizer(systemTokenCustomizer)
        return DelegatingOAuth2TokenGenerator(
            jwtGenerator,
            OAuth2AccessTokenGenerator(),
            OAuth2RefreshTokenGenerator(),
        )
    }

    /**
     * Client-credentials tokens carry the marker claim the principal
     * converter branches on, plus the client's organization. Scopes are
     * flattened to the space-separated form the converter already parses.
     */
    @Bean
    fun systemTokenCustomizer(
        oauthClientRepository: OAuthClientRepository,
    ): OAuth2TokenCustomizer<JwtEncodingContext> =
        OAuth2TokenCustomizer { context ->
            if (context.authorizationGrantType != AuthorizationGrantType.CLIENT_CREDENTIALS) {
                return@OAuth2TokenCustomizer
            }
            val client = oauthClientRepository.findByClientIdentifier(context.registeredClient.clientId)
                ?: return@OAuth2TokenCustomizer
            context.claims.claim(
                JwtPrincipalAuthenticationConverter.SYSTEM_PRINCIPAL_CLAIM,
                JwtPrincipalAuthenticationConverter.SYSTEM_PRINCIPAL_VALUE,
            )
            client.organizationId?.let {
                context.claims.claim(JwtClaimNames.ORGANIZATION_ID, it.value.toString())
            }
            context.claims.claim(JwtClaimNames.SCOPE, context.authorizedScopes.joinToString(" "))
        }
}
