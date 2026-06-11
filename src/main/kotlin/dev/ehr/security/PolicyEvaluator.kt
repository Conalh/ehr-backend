package dev.ehr.security

import dev.ehr.identity.MembershipRole
import org.springframework.stereotype.Service

@Service
class PolicyEvaluator(
    private val relationshipResolver: RelationshipResolver,
    private val enforcementModeResolver: EnforcementModeResolver,
    private val breakGlassAccessor: BreakGlassAccessor,
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

        return evaluateCompartment(
            principal = principal,
            request = request,
            rule = rule,
            roleBasis = compatibleRoles,
            scopeBasis = compatibleScopes,
        )
    }

    /**
     * Compartment evaluation per the organization's rollout posture:
     * off = skip; shadow = resolve and record, never deny; enforced = deny
     * relationship-less clinical-record access (break-glass excepted, reads
     * only — opening an encounter is the write path's relationship grant).
     */
    private fun evaluateCompartment(
        principal: SecurityPrincipal,
        request: PolicyEvaluationRequest,
        rule: PolicyRule,
        roleBasis: List<MembershipRole>,
        scopeBasis: List<SecurityScope>,
    ): PolicyDecision {
        fun allowed(
            relationshipBasis: RelationshipBasis? = null,
            purposeOfUse: String? = null,
            breakGlassReason: String? = null,
        ): PolicyDecision = decision(
            principal = principal,
            request = request,
            allowed = true,
            roleBasis = roleBasis,
            scopeBasis = scopeBasis,
            reasonCode = PolicyReasonCode.ALLOWED,
            relationshipBasis = relationshipBasis,
            purposeOfUse = purposeOfUse,
            breakGlassReason = breakGlassReason,
        )

        if (!rule.requiresRelationship || request.patientId == null) {
            return allowed()
        }
        val mode = enforcementModeResolver.resolve(request.organizationId)
        if (mode == CompartmentEnforcementMode.OFF) {
            return allowed()
        }
        // Principals without a user identity (system apps) cannot hold a
        // treatment relationship: they shadow as null and fail closed when
        // enforced.
        val relationshipBasis = principal.subject.userId?.let { userId ->
            relationshipResolver.resolve(
                organizationId = request.organizationId,
                userId = userId,
                patientId = request.patientId,
            )
        }
        if (relationshipBasis != null || mode == CompartmentEnforcementMode.SHADOW) {
            return allowed(relationshipBasis = relationshipBasis)
        }
        if (request.operation == PolicyOperation.READ) {
            val reason = breakGlassAccessor.currentReason()
            if (reason != null) {
                return allowed(
                    relationshipBasis = RelationshipBasis.BREAK_GLASS,
                    purposeOfUse = PURPOSE_EMERGENCY_TREATMENT,
                    breakGlassReason = reason,
                )
            }
        }
        return decision(
            principal = principal,
            request = request,
            allowed = false,
            roleBasis = roleBasis,
            scopeBasis = scopeBasis,
            reasonCode = PolicyReasonCode.NO_TREATMENT_RELATIONSHIP,
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
        purposeOfUse: String? = null,
        breakGlassReason: String? = null,
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
            purposeOfUse = purposeOfUse,
            breakGlassReason = breakGlassReason,
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
        const val POLICY_VERSION = "policy-spine-v18"

        // HL7 v3 PurposeOfUse code for emergency treatment (break-glass).
        const val PURPOSE_EMERGENCY_TREATMENT = "ETREAT"

        private val CLINICIAN_ONLY = setOf(MembershipRole.CLINICIAN)
        private val CLINICIAN_AND_STAFF = setOf(MembershipRole.CLINICIAN, MembershipRole.STAFF)
        private val CLINICIAN_AND_SYSTEM_APP = setOf(MembershipRole.CLINICIAN, MembershipRole.SYSTEM_APP)
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
            // Bulk export covers the whole population: clinicians and
            // backend-services clients (SYSTEM_APP), with wildcard scopes.
            // Export is the only SYSTEM_APP surface (AS design decision 3).
            PolicyResourceType.EXPORT to mapOf(
                PolicyOperation.READ to PolicyRule(CLINICIAN_AND_SYSTEM_APP, "*", requiresWildcardResource = true),
                PolicyOperation.WRITE to PolicyRule(CLINICIAN_AND_SYSTEM_APP, "*", requiresWildcardResource = true),
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
