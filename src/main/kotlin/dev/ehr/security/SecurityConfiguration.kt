package dev.ehr.security

import dev.ehr.identity.MembershipRepository
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
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
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
    ): JwtPrincipalAuthenticationConverter =
        JwtPrincipalAuthenticationConverter(
            userRepository = userRepository,
            organizationRepository = organizationRepository,
            membershipRepository = membershipRepository,
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

    @Bean
    fun jwtDecoder(properties: EhrProperties): JwtDecoder =
        NimbusJwtDecoder.withSecretKey(devJwtSecretKey(properties.security.devJwtSecret))
            .macAlgorithm(MacAlgorithm.HS256)
            .build()

    private fun devJwtSecretKey(devJwtSecret: String): SecretKey =
        SecretKeySpec(devJwtSecret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256")
}
