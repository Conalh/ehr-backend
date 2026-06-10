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

        val resourceRules = rules[request.resourceType]
            ?: return decision(
                principal = principal,
                request = request,
                allowed = false,
                roleBasis = emptyList(),
                scopeBasis = emptyList(),
                reasonCode = PolicyReasonCode.UNSUPPORTED_RESOURCE,
            )

        val rule = resourceRules[request.operation]
            ?: return decision(
                principal = principal,
                request = request,
                allowed = false,
                roleBasis = emptyList(),
                scopeBasis = emptyList(),
                reasonCode = PolicyReasonCode.UNSUPPORTED_OPERATION,
            )

        val compatibleRoles = principal.membership.roles.filter { it in rule.roles }
        val compatibleScopes = principal.subject.scopes.filter { scope ->
            scopeAuthorizes(scope, rule, request.operation)
        }

        if (compatibleRoles.isEmpty()) {
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
                roleBasis = compatibleRoles,
                scopeBasis = emptyList(),
                reasonCode = PolicyReasonCode.INSUFFICIENT_SCOPE,
            )
        }

        return decision(
            principal = principal,
            request = request,
            allowed = true,
            roleBasis = compatibleRoles,
            scopeBasis = compatibleScopes,
            reasonCode = PolicyReasonCode.ALLOWED,
        )
    }

    private fun scopeAuthorizes(
        scope: SecurityScope,
        rule: PolicyRule,
        operation: PolicyOperation,
    ): Boolean {
        val smartScope = SmartScope.parse(scope.rawValue) ?: return false
        // Patient-context scopes need launch context, which does not exist yet: fail closed.
        if (smartScope.context == SmartContext.PATIENT) {
            return false
        }
        val resourceCovered = if (rule.requiresWildcardResource) {
            smartScope.resourceType == "*"
        } else {
            smartScope.coversResource(rule.fhirResource)
        }
        if (!resourceCovered) {
            return false
        }
        return when (operation) {
            PolicyOperation.READ -> smartScope.canRead
            PolicyOperation.WRITE -> smartScope.canWrite
        }
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

    private data class PolicyRule(
        val roles: Set<MembershipRole>,
        val fhirResource: String,
        // The chart is a whole-compartment composite: only wildcard scopes cover it.
        val requiresWildcardResource: Boolean = false,
    )

    companion object {
        const val POLICY_VERSION = "policy-spine-v13"

        private val CLINICIAN_ONLY = setOf(MembershipRole.CLINICIAN)
        private val CLINICIAN_AND_STAFF = setOf(MembershipRole.CLINICIAN, MembershipRole.STAFF)
        private val ADMINS = setOf(MembershipRole.ORG_ADMIN, MembershipRole.SYSTEM_ADMIN)

        private fun readWrite(
            readRoles: Set<MembershipRole>,
            writeRoles: Set<MembershipRole>,
            fhirResource: String,
        ): Map<PolicyOperation, PolicyRule> = mapOf(
            PolicyOperation.READ to PolicyRule(readRoles, fhirResource),
            PolicyOperation.WRITE to PolicyRule(writeRoles, fhirResource),
        )

        private val rules: Map<PolicyResourceType, Map<PolicyOperation, PolicyRule>> = mapOf(
            PolicyResourceType.ORGANIZATION to mapOf(
                PolicyOperation.READ to PolicyRule(ADMINS, "Organization"),
            ),
            PolicyResourceType.PATIENT to readWrite(CLINICIAN_AND_STAFF, CLINICIAN_ONLY, "Patient"),
            // Encounters are scheduling-adjacent, so staff retain read access.
            PolicyResourceType.ENCOUNTER to readWrite(CLINICIAN_AND_STAFF, CLINICIAN_ONLY, "Encounter"),
            // Everything below is clinical-record data: clinician-only.
            PolicyResourceType.CONDITION to readWrite(CLINICIAN_ONLY, CLINICIAN_ONLY, "Condition"),
            PolicyResourceType.ALLERGY to readWrite(CLINICIAN_ONLY, CLINICIAN_ONLY, "AllergyIntolerance"),
            PolicyResourceType.OBSERVATION to readWrite(CLINICIAN_ONLY, CLINICIAN_ONLY, "Observation"),
            PolicyResourceType.MEDICATION to readWrite(CLINICIAN_ONLY, CLINICIAN_ONLY, "MedicationStatement"),
            PolicyResourceType.NOTE to readWrite(CLINICIAN_ONLY, CLINICIAN_ONLY, "DocumentReference"),
            PolicyResourceType.ORDER to readWrite(CLINICIAN_ONLY, CLINICIAN_ONLY, "ServiceRequest"),
            PolicyResourceType.DIAGNOSTIC_REPORT to readWrite(CLINICIAN_ONLY, CLINICIAN_ONLY, "DiagnosticReport"),
            PolicyResourceType.PROVENANCE to mapOf(
                PolicyOperation.READ to PolicyRule(CLINICIAN_ONLY, "Provenance"),
            ),
            PolicyResourceType.CHART to mapOf(
                PolicyOperation.READ to PolicyRule(CLINICIAN_ONLY, "*", requiresWildcardResource = true),
            ),
            // Client management is an organization-settings function: admin-only,
            // and like the chart it needs wildcard scopes (it is not a FHIR resource).
            PolicyResourceType.OAUTH_CLIENT to mapOf(
                PolicyOperation.READ to PolicyRule(ADMINS, "*", requiresWildcardResource = true),
                PolicyOperation.WRITE to PolicyRule(ADMINS, "*", requiresWildcardResource = true),
            ),
        )
    }
}
