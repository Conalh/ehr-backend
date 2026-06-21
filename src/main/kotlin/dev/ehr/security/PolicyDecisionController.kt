package dev.ehr.security

import dev.ehr.identity.OrganizationId
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/v1/security")
class PolicyDecisionController(
    private val policyEvaluator: PolicyEvaluator,
    private val auditEventService: AuditEventService,
) {
    @GetMapping("/policy-check")
    fun policyCheck(
        authentication: Authentication,
        @RequestParam organizationId: UUID?,
    ): PolicyDecisionResponse {
        val principal = authentication.securityPrincipal()
        val requestedOrganizationId = organizationId
            ?.let(::OrganizationId)
            ?: principal.organization.organizationId
        val decision = policyEvaluator.evaluate(
            principal = principal,
            request = PolicyEvaluationRequest(
                resourceType = PolicyResourceType.ORGANIZATION,
                operation = PolicyOperation.READ,
                organizationId = requestedOrganizationId,
            ),
        )
        auditEventService.recordPolicyDecision(decision)

        return decision.toResponse()
    }
}

data class PolicyDecisionResponse(
    val allowed: Boolean,
    val subjectUserId: String?,
    val organizationId: String,
    val membershipId: String,
    val resourceType: PolicyResourceType,
    val operation: PolicyOperation,
    val roleBasis: List<String>,
    val scopeBasis: List<SecurityScopeResponse>,
    val relationshipBasis: String?,
    val purposeOfUse: String?,
    val policyVersion: String,
    val reasonCode: PolicyReasonCode,
)

data class SecurityScopeResponse(
    val rawValue: String,
)

private fun PolicyDecision.toResponse(): PolicyDecisionResponse =
    PolicyDecisionResponse(
        allowed = allowed,
        subjectUserId = subjectUserId?.value?.toString(),
        organizationId = organizationId.value.toString(),
        membershipId = membershipId.value.toString(),
        resourceType = resourceType,
        operation = operation,
        roleBasis = roleBasis.map { it.dbValue },
        scopeBasis = scopeBasis.map { SecurityScopeResponse(it.rawValue) },
        relationshipBasis = relationshipBasis?.dbValue,
        purposeOfUse = purposeOfUse,
        policyVersion = policyVersion,
        reasonCode = reasonCode,
    )
