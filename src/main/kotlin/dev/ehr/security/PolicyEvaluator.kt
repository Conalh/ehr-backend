package dev.ehr.security

import dev.ehr.identity.MembershipRole
import org.springframework.stereotype.Service

@Service
class PolicyEvaluator(
    private val relationshipResolver: RelationshipResolver,
) {
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
            relationshipBasis = resolveRelationship(principal, request, rule),
        )
    }

    /**
     * Shadow-mode compartment evaluation: record what relationship (if any)
     * connects the user to the patient. Never affects the decision in H2.
     */
    private fun resolveRelationship(
        principal: SecurityPrincipal,
        request: PolicyEvaluationRequest,
        rule: PolicyRule,
    ): RelationshipBasis? {
        if (!rule.requiresRelationship) {
            return null
        }
        val patientId = request.patientId ?: return null
        val userId = principal.subject.userId ?: return null
        return relationshipResolver.resolve(
            organizationId = request.organizationId,
            userId = userId,
            patientId = patientId,
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
        relationshipBasis: RelationshipBasis? = null,
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
            relationshipBasis = relationshipBasis,
            purposeOfUse = null,
            policyVersion = POLICY_VERSION,
            reasonCode = reasonCode,
        )

    private data class PolicyRule(
        val roles: Set<MembershipRole>,
        val fhirResource: String,
        // The chart is a whole-compartment composite: only wildcard scopes cover it.
        val requiresWildcardResource: Boolean = false,
        // Clinical-record rules shadow-resolve the treatment relationship (design decision 3).
        val requiresRelationship: Boolean = false,
    )

    companion object {
        const val POLICY_VERSION = "policy-spine-v16"

        private val CLINICIAN_ONLY = setOf(MembershipRole.CLINICIAN)
        private val CLINICIAN_AND_STAFF = setOf(MembershipRole.CLINICIAN, MembershipRole.STAFF)
        private val ADMINS = setOf(MembershipRole.ORG_ADMIN, MembershipRole.SYSTEM_ADMIN)

        private fun readWrite(
            readRoles: Set<MembershipRole>,
            writeRoles: Set<MembershipRole>,
            fhirResource: String,
            requiresRelationship: Boolean = false,
        ): Map<PolicyOperation, PolicyRule> = mapOf(
            PolicyOperation.READ to PolicyRule(readRoles, fhirResource, requiresRelationship = requiresRelationship),
            PolicyOperation.WRITE to PolicyRule(writeRoles, fhirResource, requiresRelationship = requiresRelationship),
        )

        private fun clinicalRecord(fhirResource: String): Map<PolicyOperation, PolicyRule> =
            readWrite(CLINICIAN_ONLY, CLINICIAN_ONLY, fhirResource, requiresRelationship = true)

        private val rules: Map<PolicyResourceType, Map<PolicyOperation, PolicyRule>> = mapOf(
            PolicyResourceType.ORGANIZATION to mapOf(
                PolicyOperation.READ to PolicyRule(ADMINS, "Organization"),
            ),
            PolicyResourceType.PATIENT to readWrite(CLINICIAN_AND_STAFF, CLINICIAN_ONLY, "Patient"),
            // Encounters are scheduling-adjacent, so staff retain read access.
            PolicyResourceType.ENCOUNTER to readWrite(CLINICIAN_AND_STAFF, CLINICIAN_ONLY, "Encounter"),
            // Everything below is clinical-record data: clinician-only, and
            // compartment-aware (shadow in H2, enforced per-org in H3).
            PolicyResourceType.CONDITION to clinicalRecord("Condition"),
            PolicyResourceType.ALLERGY to clinicalRecord("AllergyIntolerance"),
            PolicyResourceType.OBSERVATION to clinicalRecord("Observation"),
            PolicyResourceType.MEDICATION to clinicalRecord("MedicationStatement"),
            PolicyResourceType.NOTE to clinicalRecord("DocumentReference"),
            PolicyResourceType.ORDER to clinicalRecord("ServiceRequest"),
            PolicyResourceType.DIAGNOSTIC_REPORT to clinicalRecord("DiagnosticReport"),
            PolicyResourceType.PROVENANCE to mapOf(
                PolicyOperation.READ to PolicyRule(CLINICIAN_ONLY, "Provenance", requiresRelationship = true),
            ),
            PolicyResourceType.CHART to mapOf(
                PolicyOperation.READ to PolicyRule(
                    CLINICIAN_ONLY,
                    "*",
                    requiresWildcardResource = true,
                    requiresRelationship = true,
                ),
            ),
            // Client management is an organization-settings function: admin-only,
            // and like the chart it needs wildcard scopes (it is not a FHIR resource).
            PolicyResourceType.OAUTH_CLIENT to mapOf(
                PolicyOperation.READ to PolicyRule(ADMINS, "*", requiresWildcardResource = true),
                PolicyOperation.WRITE to PolicyRule(ADMINS, "*", requiresWildcardResource = true),
            ),
            // Bulk export covers the whole population: clinician-only with wildcard scopes.
            // System-app requesters arrive with system-app principals (deferred).
            PolicyResourceType.EXPORT to mapOf(
                PolicyOperation.READ to PolicyRule(CLINICIAN_ONLY, "*", requiresWildcardResource = true),
                PolicyOperation.WRITE to PolicyRule(CLINICIAN_ONLY, "*", requiresWildcardResource = true),
            ),
            // Care-team membership management: clinicians and org admins.
            PolicyResourceType.CARE_TEAM to mapOf(
                PolicyOperation.READ to PolicyRule(
                    setOf(MembershipRole.CLINICIAN, MembershipRole.ORG_ADMIN),
                    "CareTeam",
                ),
                PolicyOperation.WRITE to PolicyRule(
                    setOf(MembershipRole.CLINICIAN, MembershipRole.ORG_ADMIN),
                    "CareTeam",
                ),
            ),
        )
    }
}
