package dev.ehr.security

import dev.ehr.identity.MembershipRepository
import dev.ehr.identity.OrganizationRepository
import dev.ehr.identity.UserRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.security.web.SecurityFilterChain
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
            .authorizeHttpRequests { authorize ->
                authorize
                    .requestMatchers("/internal/health", "/actuator/health", "/actuator/health/**").permitAll()
                    .requestMatchers("/api/v1/**", "/fhir/r4/**").authenticated()
                    .anyRequest().permitAll()
            }
            .oauth2ResourceServer { resourceServer ->
                resourceServer.jwt { jwt ->
                    jwt.jwtAuthenticationConverter(jwtPrincipalAuthenticationConverter)
                }
            }
            .build()

    @Bean
    fun jwtDecoder(
        @Value("\${ehr.security.dev-jwt-secret}") devJwtSecret: String,
    ): JwtDecoder =
        NimbusJwtDecoder.withSecretKey(devJwtSecretKey(devJwtSecret))
            .macAlgorithm(MacAlgorithm.HS256)
            .build()

    private fun devJwtSecretKey(devJwtSecret: String): SecretKey {
        val keyBytes = devJwtSecret.toByteArray(StandardCharsets.UTF_8)
        require(keyBytes.size >= MIN_HS256_KEY_BYTES) {
            "ehr.security.dev-jwt-secret must be at least $MIN_HS256_KEY_BYTES bytes for HS256"
        }
        return SecretKeySpec(keyBytes, "HmacSHA256")
    }

    private companion object {
        const val MIN_HS256_KEY_BYTES = 32
    }
}
