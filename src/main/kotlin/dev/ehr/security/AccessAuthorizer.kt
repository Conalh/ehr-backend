package dev.ehr.security

import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

@Service
class AccessAuthorizer(
    private val policyEvaluator: PolicyEvaluator,
    private val auditEventService: AuditEventService,
) {
    fun authorize(
        principal: SecurityPrincipal,
        resourceType: PolicyResourceType,
        operation: PolicyOperation,
        forbiddenMessage: String,
        patientId: UUID? = null,
        resourceId: UUID? = null,
    ): PolicyDecision {
        val decision = evaluate(
            principal = principal,
            resourceType = resourceType,
            operation = operation,
            patientId = patientId,
        )
        if (!decision.allowed) {
            auditEventService.recordDeniedAccess(
                decision = decision,
                patientId = patientId,
                resourceId = resourceId,
            )
            throw ResponseStatusException(HttpStatus.FORBIDDEN, forbiddenMessage)
        }
        return decision
    }

    fun evaluate(
        principal: SecurityPrincipal,
        resourceType: PolicyResourceType,
        operation: PolicyOperation,
        patientId: UUID? = null,
    ): PolicyDecision =
        policyEvaluator.evaluate(
            principal = principal,
            request = PolicyEvaluationRequest(
                resourceType = resourceType,
                operation = operation,
                organizationId = principal.organization.organizationId,
                patientId = patientId,
            ),
        )
}
