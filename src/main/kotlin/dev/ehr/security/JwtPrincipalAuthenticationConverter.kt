package dev.ehr.security

import dev.ehr.identity.MembershipRepository
import dev.ehr.identity.OAuthClientId
import dev.ehr.identity.Organization
import dev.ehr.identity.OrganizationId
import dev.ehr.identity.OrganizationRepository
import dev.ehr.identity.OrganizationStatus
import dev.ehr.identity.UserRepository
import dev.ehr.identity.UserStatus
import org.springframework.core.convert.converter.Converter
import org.springframework.security.authentication.AbstractAuthenticationToken
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.core.OAuth2AuthenticationException
import org.springframework.security.oauth2.core.OAuth2Error
import org.springframework.security.oauth2.jwt.Jwt
import java.util.UUID

class JwtPrincipalAuthenticationConverter(
    private val userRepository: UserRepository,
    private val organizationRepository: OrganizationRepository,
    private val membershipRepository: MembershipRepository,
) : Converter<Jwt, AbstractAuthenticationToken> {
    override fun convert(jwt: Jwt): AbstractAuthenticationToken {
        val externalSubject = jwt.subject?.trim()?.takeIf { it.isNotEmpty() }
            ?: invalidToken("JWT subject is required")
        val user = userRepository.findByExternalSubject(externalSubject)
            ?: invalidToken("JWT subject is not linked to a user")
        if (user.status != UserStatus.ACTIVE) {
            invalidToken("JWT subject is not active")
        }

        val organization = resolveOrganization(jwt)
        if (organization.status != OrganizationStatus.ACTIVE) {
            invalidToken("JWT organization is not active")
        }

        val membership = membershipRepository.findActiveByOrganizationAndUser(
            organizationId = organization.id,
            userId = user.id,
        ) ?: invalidToken("JWT subject is not an active member of the organization")
        val roles = membershipRepository.findRoles(membership.id)
        val scopes = extractScopes(jwt)
        val principal = SecurityPrincipal(
            subject = AuthenticatedSubject(
                externalSubject = externalSubject,
                userId = user.id,
                clientId = parseOptionalClientId(jwt),
                scopes = scopes,
            ),
            organization = OrganizationContext(
                organizationId = organization.id,
            ),
            membership = MembershipContext(
                membershipId = membership.id,
                roles = roles,
            ),
        )
        val authorities = scopes.map { SimpleGrantedAuthority("SCOPE_${it.rawValue}") }

        return UsernamePasswordAuthenticationToken(principal, jwt, authorities)
    }

    private fun resolveOrganization(jwt: Jwt): Organization {
        val organizationById = jwt.getClaimAsString(JwtClaimNames.ORGANIZATION_ID)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { rawId ->
                val id = parseUuid(rawId, "JWT organization ID is not a UUID")
                organizationRepository.findById(OrganizationId(id))
                    ?: invalidToken("JWT organization ID is not linked to an organization")
            }

        val organizationBySlug = jwt.getClaimAsString(JwtClaimNames.ORGANIZATION_SLUG)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { slug ->
                organizationRepository.findBySlug(slug)
                    ?: invalidToken("JWT organization slug is not linked to an organization")
            }

        if (organizationById != null && organizationBySlug != null && organizationById.id != organizationBySlug.id) {
            invalidToken("JWT organization ID and slug refer to different organizations")
        }

        return organizationById
            ?: organizationBySlug
            ?: invalidToken("JWT organization ID or slug is required")
    }

    private fun extractScopes(jwt: Jwt): List<SecurityScope> {
        val rawScopes = mutableListOf<String>()
        jwt.getClaimAsString(JwtClaimNames.SCOPE)
            ?.takeIf { it.isNotBlank() }
            ?.let(rawScopes::add)

        when (val scp = jwt.claims[JwtClaimNames.SCOPES]) {
            is String -> rawScopes.add(scp)
            is Collection<*> -> scp.filterIsInstance<String>().forEach(rawScopes::add)
        }

        return SecurityScope.parse(rawScopes.joinToString(" "))
    }

    private fun parseOptionalClientId(jwt: Jwt): OAuthClientId? =
        jwt.getClaimAsString(JwtClaimNames.CLIENT_ID)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { OAuthClientId(parseUuid(it, "JWT client ID is not a UUID")) }

    private fun parseUuid(
        rawValue: String,
        invalidMessage: String,
    ): UUID =
        try {
            UUID.fromString(rawValue)
        } catch (_: IllegalArgumentException) {
            invalidToken(invalidMessage)
        }

    private fun invalidToken(description: String): Nothing =
        throw OAuth2AuthenticationException(
            OAuth2Error("invalid_token", description, null),
        )
}
