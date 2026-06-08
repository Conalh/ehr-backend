package dev.ehr.security

import dev.ehr.identity.OAuthClientId
import dev.ehr.identity.OrganizationId
import dev.ehr.identity.MembershipId
import dev.ehr.identity.MembershipRole
import dev.ehr.identity.UserId

data class AuthenticatedSubject(
    val externalSubject: String,
    val userId: UserId? = null,
    val clientId: OAuthClientId? = null,
    val scopes: List<SecurityScope> = emptyList(),
)

data class OrganizationContext(
    val organizationId: OrganizationId,
)

data class MembershipContext(
    val membershipId: MembershipId,
    val roles: List<MembershipRole>,
)

data class SecurityPrincipal(
    val subject: AuthenticatedSubject,
    val organization: OrganizationContext,
    val membership: MembershipContext,
)
