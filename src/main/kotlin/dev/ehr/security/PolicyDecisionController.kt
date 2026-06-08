package dev.ehr.security

import org.springframework.http.HttpStatus
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

@RestController
@RequestMapping("/api/v1/security")
class PolicyDecisionController(
    private val policyEvaluator: PolicyEvaluator,
) {
    @GetMapping("/policy-check")
    fun policyCheck(authentication: Authentication): PolicyDecisionResponse {
        val principal = authentication.principal as? SecurityPrincipal
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Security principal is not available")
        val decision = policyEvaluator.evaluate(
            principal = principal,
            request = PolicyEvaluationRequest(
                resourceType = PolicyResourceType.ORGANIZATION,
                operation = PolicyOperation.READ,
                organizationId = principal.organization.organizationId,
            ),
        )

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
        relationshipBasis = relationshipBasis,
        purposeOfUse = purposeOfUse,
        policyVersion = policyVersion,
        reasonCode = reasonCode,
    )
