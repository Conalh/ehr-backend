package dev.ehr.security

import dev.ehr.identity.MembershipId
import dev.ehr.identity.MembershipRole
import dev.ehr.identity.OrganizationId
import dev.ehr.identity.UserId
import java.util.UUID

enum class PolicyResourceType {
    ORGANIZATION,
    PATIENT,
    ENCOUNTER,
    CONDITION,
    ALLERGY,
    OBSERVATION,
    MEDICATION,
    NOTE,
    CHART,
    PROVENANCE,
    ORDER,
    DIAGNOSTIC_REPORT,
    OAUTH_CLIENT,
    EXPORT,
    CARE_TEAM,
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

/** What satisfied the patient-compartment requirement for a decision. */
enum class RelationshipBasis(val dbValue: String) {
    CARE_TEAM_MEMBER("care-team-member"),
    ENCOUNTER_DERIVED("encounter-derived"),
    BREAK_GLASS("break-glass"),
}

data class PolicyEvaluationRequest(
    val resourceType: PolicyResourceType,
    val operation: PolicyOperation,
    val organizationId: OrganizationId,
    // The patient compartment being entered, when the caller knows it.
    // Fetch-first paths re-evaluate with the discovered patient.
    val patientId: UUID? = null,
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
    val relationshipBasis: RelationshipBasis?,
    val purposeOfUse: String?,
    val policyVersion: String,
    val reasonCode: PolicyReasonCode,
)
