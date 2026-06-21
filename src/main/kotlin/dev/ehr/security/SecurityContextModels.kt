package dev.ehr.security

import dev.ehr.identity.OAuthClientId
import dev.ehr.identity.OrganizationId
import dev.ehr.identity.MembershipId
import dev.ehr.identity.MembershipRole
import dev.ehr.identity.TenantScope
import dev.ehr.identity.UserId
import java.util.UUID

data class AuthenticatedSubject(
    val externalSubject: String,
    val userId: UserId? = null,
    val clientId: OAuthClientId? = null,
    val scopes: List<SecurityScope> = emptyList(),
    // The SMART launch context: the patient this token is bound to, when the
    // authorization carried a standalone patient launch.
    val launchPatientId: UUID? = null,
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

fun SecurityPrincipal.tenantScope(): TenantScope =
    TenantScope(organization.organizationId)

/**
 * The launched patient, when this principal's clinical access is bound to
 * one: launch context present and every parseable SMART scope is
 * patient-context. Services use it to constrain result sets that the
 * evaluator cannot see (e.g. identifier search).
 */
fun SecurityPrincipal.launchBoundPatientId(): UUID? {
    val launchPatientId = subject.launchPatientId ?: return null
    val smartScopes = subject.scopes.mapNotNull { SmartScope.parse(it.rawValue) }
    return launchPatientId.takeIf {
        smartScopes.isNotEmpty() && smartScopes.all { it.context == SmartContext.PATIENT }
    }
}
