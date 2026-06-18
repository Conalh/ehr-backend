package dev.ehr.authz

import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.source.ImmutableJWKSet
import com.nimbusds.jose.jwk.source.JWKSource
import com.nimbusds.jose.proc.SecurityContext
import dev.ehr.identity.MembershipRepository
import dev.ehr.identity.OAuthClientRepository
import dev.ehr.identity.PractitionerRepository
import dev.ehr.identity.UserRepository
import dev.ehr.identity.UserStatus
import dev.ehr.runtime.EhrProperties
import dev.ehr.security.JwtClaimNames
import dev.ehr.security.JwtPrincipalAuthenticationConverter
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.Order
import org.springframework.http.MediaType
import org.springframework.security.config.Customizer
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.core.Authentication
import org.springframework.security.core.userdetails.User
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.core.userdetails.UsernameNotFoundException
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.oauth2.core.AuthorizationGrantType
import org.springframework.security.oauth2.core.OAuth2AuthenticationException
import org.springframework.security.oauth2.core.OAuth2Error
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType
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
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint
import org.springframework.security.web.util.matcher.MediaTypeRequestMatcher
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.util.UUID

/**
 * Embedded authorization server (design decision 1B). AS1: client-credentials
 * tokens for backend services. AS2: authorization code + PKCE + rotating
 * refresh + OIDC for user apps, authenticated by a deliberately plain dev
 * login (shared password — users carry no credentials; synthetic data only).
 * The RSA keypair is generated at startup — tokens die with the process, the
 * documented dev posture.
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
            .with(authorizationServerConfigurer) {
                it.oidc(Customizer.withDefaults())
                it.tokenEndpoint { tokenEndpoint ->
                    tokenEndpoint.accessTokenResponseHandler(launchAwareTokenResponseHandler())
                }
            }
            // Interpose the patient picker before the authorize endpoint
            // processes a launch/patient request.
            .addFilterBefore(
                PatientLaunchFilter(),
                org.springframework.security.web.authentication.preauth.AbstractPreAuthenticatedProcessingFilter::class.java,
            )
            .authorizeHttpRequests { it.anyRequest().authenticated() }
            // Browser-shaped requests to /oauth/authorize go through the dev
            // login; everything programmatic keeps its OAuth error responses
            // (Accept: */* must not count as a browser, hence ignoring ALL).
            .exceptionHandling {
                val htmlRequest = MediaTypeRequestMatcher(MediaType.TEXT_HTML)
                htmlRequest.setIgnoredMediaTypes(setOf(MediaType.ALL))
                it.defaultAuthenticationEntryPointFor(
                    LoginUrlAuthenticationEntryPoint("/login"),
                    htmlRequest,
                )
            }
        return http.build()
    }

    @Bean
    @Order(1)
    fun devLoginSecurityFilterChain(http: HttpSecurity): SecurityFilterChain =
        http
            .securityMatcher("/login", "/logout", "/launch/**")
            .authorizeHttpRequests {
                it.requestMatchers("/launch/**").authenticated()
                it.anyRequest().permitAll()
            }
            .formLogin(Customizer.withDefaults())
            .build()

    /**
     * Dev login: any active user, one shared password from configuration.
     * No credentials are stored on users; this is the AS2 sibling of the
     * accepted AS3 patient-picker decision.
     */
    @Bean
    fun devLoginUserDetailsService(
        userRepository: UserRepository,
        properties: EhrProperties,
        passwordEncoder: PasswordEncoder,
    ): UserDetailsService {
        val encodedSharedPassword = passwordEncoder.encode(properties.security.devLoginPassword)
        return UserDetailsService { username ->
            val user = userRepository.findByExternalSubject(username)
                ?.takeIf { it.status == UserStatus.ACTIVE }
                ?: throw UsernameNotFoundException("Unknown or inactive user")
            User.withUsername(user.externalSubject)
                .password(encodedSharedPassword)
                .authorities("ROLE_DEV_LOGIN")
                .build()
        }
    }

    @Bean
    fun authorizationServerSettings(properties: EhrProperties): AuthorizationServerSettings =
        AuthorizationServerSettings.builder()
            .issuer(properties.security.issuer)
            // The paths the SMART discovery document advertises.
            .authorizationEndpoint("/oauth/authorize")
            .tokenEndpoint("/oauth/token")
            .tokenRevocationEndpoint("/oauth/revoke")
            .jwkSetEndpoint("/oauth/jwks")
            .build()

    @Bean
    fun authorizationService(): org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService =
        LaunchContextAuthorizationService(
            org.springframework.security.oauth2.server.authorization.InMemoryOAuth2AuthorizationService(),
        )

    /**
     * SMART launch context: the token response carries the launched patient
     * as the `patient` parameter, read back from our own access token claim.
     */
    private fun launchAwareTokenResponseHandler(): org.springframework.security.web.authentication.AuthenticationSuccessHandler {
        val converter = org.springframework.security.oauth2.core.http.converter.OAuth2AccessTokenResponseHttpMessageConverter()
        return org.springframework.security.web.authentication.AuthenticationSuccessHandler { _, response, authentication ->
            val tokenAuthentication =
                authentication as org.springframework.security.oauth2.server.authorization.authentication.OAuth2AccessTokenAuthenticationToken
            val accessToken = tokenAuthentication.accessToken
            val additionalParameters = tokenAuthentication.additionalParameters.toMutableMap()
            runCatching {
                com.nimbusds.jwt.JWTParser.parse(accessToken.tokenValue)
                    .jwtClaimsSet.getStringClaim(JwtClaimNames.LAUNCH_PATIENT)
            }.getOrNull()?.let { additionalParameters["patient"] = it }

            val builder = org.springframework.security.oauth2.core.endpoint.OAuth2AccessTokenResponse
                .withToken(accessToken.tokenValue)
                .tokenType(accessToken.tokenType)
                .scopes(accessToken.scopes)
            val issuedAt = accessToken.issuedAt
            val expiresAt = accessToken.expiresAt
            if (issuedAt != null && expiresAt != null) {
                builder.expiresIn(java.time.temporal.ChronoUnit.SECONDS.between(issuedAt, expiresAt))
            }
            tokenAuthentication.refreshToken?.let { builder.refreshToken(it.tokenValue) }
            if (additionalParameters.isNotEmpty()) {
                builder.additionalParameters(additionalParameters)
            }
            converter.write(
                builder.build(),
                null,
                org.springframework.http.server.ServletServerHttpResponse(response),
            )
        }
    }

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
        tokenCustomizer: OAuth2TokenCustomizer<JwtEncodingContext>,
    ): OAuth2TokenGenerator<*> {
        val jwtGenerator = JwtGenerator(NimbusJwtEncoder(jwkSource))
        jwtGenerator.setJwtCustomizer(tokenCustomizer)
        return DelegatingOAuth2TokenGenerator(
            jwtGenerator,
            OAuth2AccessTokenGenerator(),
            OAuth2RefreshTokenGenerator(),
        )
    }

    /**
     * Access tokens carry the claims the resource-server converter already
     * parses: a flattened scope string plus the organization — the client's
     * org for system tokens, the user's single active membership for user
     * tokens (multi-org selection is a recorded deferral; issuance fails
     * closed with a clear error).
     */
    @Bean
    fun tokenCustomizer(
        oauthClientRepository: OAuthClientRepository,
        userRepository: UserRepository,
        membershipRepository: MembershipRepository,
        practitionerRepository: PractitionerRepository,
        properties: EhrProperties,
    ): OAuth2TokenCustomizer<JwtEncodingContext> =
        OAuth2TokenCustomizer { context ->
            // OIDC id_token: fhirUser only when the scope was granted AND the
            // user has a practitioner identity — never a dangling reference.
            if (context.tokenType.value == "id_token") {
                if ("fhirUser" in context.authorizedScopes) {
                    val user = userRepository.findByExternalSubject(context.getPrincipal<Authentication>().name)
                    val practitioner = user?.let { practitionerRepository.findByUserId(it.id) }
                    if (practitioner != null) {
                        context.claims.claim(
                            "fhirUser",
                            "${properties.security.issuer}/fhir/r4/Practitioner/${practitioner.id.value}",
                        )
                    }
                }
                return@OAuth2TokenCustomizer
            }
            if (context.tokenType != OAuth2TokenType.ACCESS_TOKEN) {
                return@OAuth2TokenCustomizer
            }
            when (context.authorizationGrantType) {
                AuthorizationGrantType.CLIENT_CREDENTIALS -> {
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
                AuthorizationGrantType.AUTHORIZATION_CODE, AuthorizationGrantType.REFRESH_TOKEN -> {
                    val externalSubject = context.getPrincipal<Authentication>().name
                    val user = userRepository.findByExternalSubject(externalSubject)
                        ?: invalidRequest("Token subject is not a known user")
                    val memberships = membershipRepository.findActiveByUser(user.id)
                    val membership = when {
                        memberships.isEmpty() -> invalidRequest(
                            "AS-issued user tokens require an active organization membership",
                        )
                        memberships.size > 1 -> invalidRequest(
                            "AS-issued user tokens require exactly one active organization membership; user has ${memberships.size}",
                        )
                        else -> memberships.first()
                    }
                    context.claims.claim(
                        JwtClaimNames.ORGANIZATION_ID,
                        membership.organizationId.value.toString(),
                    )
                    context.claims.claim(JwtClaimNames.SCOPE, context.authorizedScopes.joinToString(" "))
                    // SMART launch context rides in the authorization (stamped
                    // at code issuance) and binds the token to one patient.
                    context.authorization
                        ?.getAttribute<UUID>(LaunchContextAuthorizationService.LAUNCH_PATIENT_ATTRIBUTE)
                        ?.let { context.claims.claim(JwtClaimNames.LAUNCH_PATIENT, it.toString()) }
                }
            }
        }

    private fun invalidRequest(description: String): Nothing =
        throw OAuth2AuthenticationException(OAuth2Error("invalid_request", description, null))
}
