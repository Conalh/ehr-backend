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
        val compatibleScopes = principal.subject.scopes.filter { it.rawValue in rule.scopes }

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
        val scopes: Set<String>,
    )

    companion object {
        const val POLICY_VERSION = "policy-spine-v6"

        private val rules: Map<PolicyResourceType, Map<PolicyOperation, PolicyRule>> = mapOf(
            PolicyResourceType.ORGANIZATION to mapOf(
                PolicyOperation.READ to PolicyRule(
                    roles = setOf(
                        MembershipRole.ORG_ADMIN,
                        MembershipRole.SYSTEM_ADMIN,
                    ),
                    scopes = setOf(
                        "user/*.read",
                        "system/*.read",
                    ),
                ),
            ),
            PolicyResourceType.PATIENT to mapOf(
                PolicyOperation.READ to PolicyRule(
                    roles = setOf(
                        MembershipRole.CLINICIAN,
                        MembershipRole.STAFF,
                    ),
                    scopes = setOf(
                        "user/Patient.read",
                        "user/*.read",
                        "system/Patient.read",
                        "system/*.read",
                    ),
                ),
                PolicyOperation.WRITE to PolicyRule(
                    roles = setOf(
                        MembershipRole.CLINICIAN,
                    ),
                    scopes = setOf(
                        "user/Patient.write",
                        "user/*.write",
                        "system/Patient.write",
                        "system/*.write",
                    ),
                ),
            ),
            PolicyResourceType.ENCOUNTER to mapOf(
                PolicyOperation.READ to PolicyRule(
                    roles = setOf(
                        MembershipRole.CLINICIAN,
                        MembershipRole.STAFF,
                    ),
                    scopes = setOf(
                        "user/Encounter.read",
                        "user/*.read",
                        "system/Encounter.read",
                        "system/*.read",
                    ),
                ),
                PolicyOperation.WRITE to PolicyRule(
                    roles = setOf(
                        MembershipRole.CLINICIAN,
                    ),
                    scopes = setOf(
                        "user/Encounter.write",
                        "user/*.write",
                        "system/Encounter.write",
                        "system/*.write",
                    ),
                ),
            ),
            // Conditions are clinical-record data: clinician-only, unlike scheduling-adjacent encounters.
            PolicyResourceType.CONDITION to mapOf(
                PolicyOperation.READ to PolicyRule(
                    roles = setOf(
                        MembershipRole.CLINICIAN,
                    ),
                    scopes = setOf(
                        "user/Condition.read",
                        "user/*.read",
                        "system/Condition.read",
                        "system/*.read",
                    ),
                ),
                PolicyOperation.WRITE to PolicyRule(
                    roles = setOf(
                        MembershipRole.CLINICIAN,
                    ),
                    scopes = setOf(
                        "user/Condition.write",
                        "user/*.write",
                        "system/Condition.write",
                        "system/*.write",
                    ),
                ),
            ),
            // Allergy lists are clinical-record data: clinician-only, like conditions.
            PolicyResourceType.ALLERGY to mapOf(
                PolicyOperation.READ to PolicyRule(
                    roles = setOf(
                        MembershipRole.CLINICIAN,
                    ),
                    scopes = setOf(
                        "user/AllergyIntolerance.read",
                        "user/*.read",
                        "system/AllergyIntolerance.read",
                        "system/*.read",
                    ),
                ),
                PolicyOperation.WRITE to PolicyRule(
                    roles = setOf(
                        MembershipRole.CLINICIAN,
                    ),
                    scopes = setOf(
                        "user/AllergyIntolerance.write",
                        "user/*.write",
                        "system/AllergyIntolerance.write",
                        "system/*.write",
                    ),
                ),
            ),
            // Observations (vitals/labs) are clinical-record data: clinician-only.
            // Category-aware staff vitals access needs attribute-bearing policy inputs (future).
            PolicyResourceType.OBSERVATION to mapOf(
                PolicyOperation.READ to PolicyRule(
                    roles = setOf(
                        MembershipRole.CLINICIAN,
                    ),
                    scopes = setOf(
                        "user/Observation.read",
                        "user/*.read",
                        "system/Observation.read",
                        "system/*.read",
                    ),
                ),
                PolicyOperation.WRITE to PolicyRule(
                    roles = setOf(
                        MembershipRole.CLINICIAN,
                    ),
                    scopes = setOf(
                        "user/Observation.write",
                        "user/*.write",
                        "system/Observation.write",
                        "system/*.write",
                    ),
                ),
            ),
        )
    }
}
