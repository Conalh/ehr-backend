package dev.ehr.security

import dev.ehr.identity.MembershipId
import dev.ehr.identity.MembershipRole
import dev.ehr.identity.OrganizationId
import dev.ehr.identity.UserId

enum class PolicyResourceType {
    ORGANIZATION,
    PATIENT,
    ENCOUNTER,
    CONDITION,
    ALLERGY,
    OBSERVATION,
    SYSTEM,
}

enum class PolicyOperation {
    READ,
    WRITE,
}

enum class PolicyReasonCode {
    ALLOWED,
    ORGANIZATION_MISMATCH,
    INSUFFICIENT_ROLE,
    INSUFFICIENT_SCOPE,
    UNSUPPORTED_RESOURCE,
    UNSUPPORTED_OPERATION,
}

data class PolicyEvaluationRequest(
    val resourceType: PolicyResourceType,
    val operation: PolicyOperation,
    val organizationId: OrganizationId,
)

data class PolicyDecision(
    val allowed: Boolean,
    val subjectUserId: UserId?,
    val organizationId: OrganizationId,
    val membershipId: MembershipId,
    val resourceType: PolicyResourceType,
    val operation: PolicyOperation,
    val roleBasis: List<MembershipRole>,
    val scopeBasis: List<SecurityScope>,
    val relationshipBasis: String?,
    val purposeOfUse: String?,
    val policyVersion: String,
    val reasonCode: PolicyReasonCode,
)
