package dev.ehr.testsupport

import com.nimbusds.jose.jwk.source.ImmutableSecret
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder
import java.nio.charset.StandardCharsets
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

@TestConfiguration
class DevJwtTestConfiguration {
    @Bean
    fun jwtEncoder(
        @Value("\${ehr.security.dev-jwt-secret}") devJwtSecret: String,
    ): JwtEncoder =
        NimbusJwtEncoder(
            ImmutableSecret(devJwtSecretKey(devJwtSecret)),
        )

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
