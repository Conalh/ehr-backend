package dev.ehr.testsupport

import dev.ehr.identity.OAuthClientId
import dev.ehr.identity.Organization
import dev.ehr.identity.User
import dev.ehr.security.JwtClaimNames
import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import org.springframework.security.oauth2.jwt.JwsHeader
import org.springframework.security.oauth2.jwt.JwtClaimsSet
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.security.oauth2.jwt.JwtEncoderParameters
import java.time.Instant

class DevJwtFactory(
    private val jwtEncoder: JwtEncoder,
) {
    fun tokenFor(
        user: User,
        organization: Organization,
        scopes: String = "user/*.read",
        clientId: OAuthClientId? = null,
    ): String =
        token(
            subject = user.externalSubject,
            organizationId = organization.id.value.toString(),
            organizationSlug = null,
            scopes = scopes,
            clientId = clientId?.value?.toString(),
        )

    fun token(
        subject: String,
        organizationId: String? = null,
        organizationSlug: String? = null,
        scopes: String = "user/*.read",
        clientId: String? = null,
    ): String {
        val now = Instant.now()
        val claimsBuilder = JwtClaimsSet.builder()
            .issuer("ehr-core-dev")
            .issuedAt(now)
            .expiresAt(now.plusSeconds(300))
            .subject(subject)
            .claim(JwtClaimNames.SCOPE, scopes)

        if (organizationId != null) {
            claimsBuilder.claim(JwtClaimNames.ORGANIZATION_ID, organizationId)
        }
        if (organizationSlug != null) {
            claimsBuilder.claim(JwtClaimNames.ORGANIZATION_SLUG, organizationSlug)
        }
        if (clientId != null) {
            claimsBuilder.claim(JwtClaimNames.CLIENT_ID, clientId)
        }

        return jwtEncoder.encode(
            JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(),
                claimsBuilder.build(),
            ),
        ).tokenValue
    }
}
