package dev.ehr.security

import com.nimbusds.jose.jwk.source.JWKSource
import com.nimbusds.jose.proc.SecurityContext
import com.nimbusds.jwt.JWTParser
import dev.ehr.identity.MembershipRepository
import dev.ehr.identity.OAuthClientRepository
import dev.ehr.identity.OrganizationRepository
import dev.ehr.identity.UserRepository
import dev.ehr.runtime.EhrProperties
import dev.ehr.runtime.RateLimitFilter
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import org.springframework.security.oauth2.jwt.BadJwtException
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configuration.OAuth2AuthorizationServerConfiguration
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter
import java.nio.charset.StandardCharsets
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

@Configuration
@EnableWebSecurity
class SecurityConfiguration {
    @Bean
    fun jwtPrincipalAuthenticationConverter(
        userRepository: UserRepository,
        organizationRepository: OrganizationRepository,
        membershipRepository: MembershipRepository,
        oauthClientRepository: OAuthClientRepository,
    ): JwtPrincipalAuthenticationConverter =
        JwtPrincipalAuthenticationConverter(
            userRepository = userRepository,
            organizationRepository = organizationRepository,
            membershipRepository = membershipRepository,
            oauthClientRepository = oauthClientRepository,
        )

    @Bean
    fun securityFilterChain(
        http: HttpSecurity,
        jwtPrincipalAuthenticationConverter: JwtPrincipalAuthenticationConverter,
    ): SecurityFilterChain =
        http
            .csrf { csrf -> csrf.disable() }
            .sessionManagement { sessions -> sessions.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .headers { headers ->
                // This service serves an API, not a site: lock content sources down entirely.
                headers.contentSecurityPolicy { csp -> csp.policyDirectives("default-src 'none'") }
                headers.referrerPolicy { referrer ->
                    referrer.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER)
                }
            }
            .authorizeHttpRequests { authorize ->
                authorize
                    .requestMatchers("/internal/health", "/actuator/health", "/actuator/health/**").permitAll()
                    .requestMatchers("/.well-known/**", "/oauth/**").permitAll()
                    // FHIR convention: the CapabilityStatement is discoverable without auth.
                    .requestMatchers("/fhir/r4/metadata").permitAll()
                    .requestMatchers("/error").permitAll()
                    .requestMatchers("/api/v1/**", "/fhir/r4/**", "/actuator/**").authenticated()
                    .anyRequest().denyAll()
            }
            .oauth2ResourceServer { resourceServer ->
                resourceServer.jwt { jwt ->
                    jwt.jwtAuthenticationConverter(jwtPrincipalAuthenticationConverter)
                }
            }
            .build()

    @Bean
    fun rateLimitFilter(properties: EhrProperties): RateLimitFilter =
        RateLimitFilter(properties)

    // Registered here rather than as @Component so MVC test slices stay
    // unaffected (the RateLimitFilter lesson). Runs after the security filter
    // chain, so the authenticated principal is available.
    @Bean
    fun tenantContextFilter(): TenantContextFilter = TenantContextFilter()

    /**
     * Routes by unverified issuer: tokens minted by the embedded
     * authorization server validate against its local JWKS (RS256);
     * everything else falls through to the HS256 dev decoder. The dev
     * decoder is registered only when ehr.security.dev-jwt-enabled is true,
     * so a default boot rejects untrusted-issuer tokens outright.
     */
    @Bean
    fun jwtDecoder(
        properties: EhrProperties,
        jwkSource: JWKSource<SecurityContext>,
    ): JwtDecoder {
        val authorizationServerDecoder = OAuth2AuthorizationServerConfiguration.jwtDecoder(jwkSource)
        val embeddedIssuer = properties.security.issuer
        if (!properties.security.devJwtEnabled) {
            return JwtDecoder { token ->
                val unverifiedIssuer = runCatching { JWTParser.parse(token).jwtClaimsSet.issuer }.getOrNull()
                if (unverifiedIssuer != embeddedIssuer) {
                    throw BadJwtException("Untrusted JWT issuer")
                }
                authorizationServerDecoder.decode(token)
            }
        }
        val devDecoder = NimbusJwtDecoder.withSecretKey(devJwtSecretKey(properties.security.devJwtSecret))
            .macAlgorithm(MacAlgorithm.HS256)
            .build()
        return JwtDecoder { token ->
            val unverifiedIssuer = runCatching { JWTParser.parse(token).jwtClaimsSet.issuer }.getOrNull()
            if (unverifiedIssuer == embeddedIssuer) {
                authorizationServerDecoder.decode(token)
            } else {
                devDecoder.decode(token)
            }
        }
    }

    private fun devJwtSecretKey(devJwtSecret: String): SecretKey =
        SecretKeySpec(devJwtSecret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256")
}
