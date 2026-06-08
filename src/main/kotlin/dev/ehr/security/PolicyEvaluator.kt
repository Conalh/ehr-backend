package dev.ehr.security

import dev.ehr.identity.MembershipRole
import org.springframework.stereotype.Service

@Service
class PolicyEvaluator {
    fun evaluate(
        principal: SecurityPrincipal,
        request: PolicyEvaluationRequest,
    ): PolicyDecision {
        if (request.organizationId != principal.organization.organizationId) {
            return decision(
                principal = principal,
                request = request,
                allowed = false,
                roleBasis = emptyList(),
                scopeBasis = emptyList(),
                reasonCode = PolicyReasonCode.ORGANIZATION_MISMATCH,
            )
        }

        if (request.resourceType != PolicyResourceType.ORGANIZATION) {
            return decision(
                principal = principal,
                request = request,
                allowed = false,
                roleBasis = emptyList(),
                scopeBasis = emptyList(),
                reasonCode = PolicyReasonCode.UNSUPPORTED_RESOURCE,
            )
        }

        if (request.operation != PolicyOperation.READ) {
            return decision(
                principal = principal,
                request = request,
                allowed = false,
                roleBasis = emptyList(),
                scopeBasis = emptyList(),
                reasonCode = PolicyReasonCode.UNSUPPORTED_OPERATION,
            )
        }

        val adminRoles = principal.membership.roles.filter { it in organizationReadRoles }
        val compatibleScopes = principal.subject.scopes.filter { it.rawValue in organizationReadScopes }

        if (adminRoles.isEmpty()) {
            return decision(
                principal = principal,
                request = request,
                allowed = false,
                roleBasis = principal.membership.roles,
                scopeBasis = compatibleScopes,
                reasonCode = PolicyReasonCode.INSUFFICIENT_ROLE,
            )
        }

        if (compatibleScopes.isEmpty()) {
            return decision(
                principal = principal,
                request = request,
                allowed = false,
                roleBasis = adminRoles,
                scopeBasis = emptyList(),
                reasonCode = PolicyReasonCode.INSUFFICIENT_SCOPE,
            )
        }

        return decision(
            principal = principal,
            request = request,
            allowed = true,
            roleBasis = adminRoles,
            scopeBasis = compatibleScopes,
            reasonCode = PolicyReasonCode.ALLOWED,
        )
    }

    private fun decision(
        principal: SecurityPrincipal,
        request: PolicyEvaluationRequest,
        allowed: Boolean,
        roleBasis: List<MembershipRole>,
        scopeBasis: List<SecurityScope>,
        reasonCode: PolicyReasonCode,
    ): PolicyDecision =
        PolicyDecision(
            allowed = allowed,
            subjectUserId = principal.subject.userId,
            organizationId = request.organizationId,
            membershipId = principal.membership.membershipId,
            resourceType = request.resourceType,
            operation = request.operation,
            roleBasis = roleBasis,
            scopeBasis = scopeBasis,
            relationshipBasis = null,
            purposeOfUse = null,
            policyVersion = POLICY_VERSION,
            reasonCode = reasonCode,
        )

    companion object {
        const val POLICY_VERSION = "policy-spine-v1"

        private val organizationReadRoles = setOf(
            MembershipRole.ORG_ADMIN,
            MembershipRole.SYSTEM_ADMIN,
        )
        private val organizationReadScopes = setOf(
            "user/*.read",
            "system/*.read",
        )
    }
}
