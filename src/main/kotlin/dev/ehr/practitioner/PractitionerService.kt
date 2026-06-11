package dev.ehr.practitioner

import dev.ehr.identity.Practitioner
import dev.ehr.identity.PractitionerId
import dev.ehr.identity.PractitionerRepository
import dev.ehr.identity.TenantScope
import dev.ehr.security.AuditEventService
import dev.ehr.security.AuditOperation
import dev.ehr.security.AuditOutcome
import dev.ehr.security.PolicyEvaluationRequest
import dev.ehr.security.PolicyEvaluator
import dev.ehr.security.PolicyOperation
import dev.ehr.security.PolicyResourceType
import dev.ehr.security.SecurityPrincipal
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException

@Service
class PractitionerService(
    private val policyEvaluator: PolicyEvaluator,
    private val auditEventService: AuditEventService,
    private val practitionerRepository: PractitionerRepository,
) {
    fun get(
        principal: SecurityPrincipal,
        practitionerId: PractitionerId,
    ): Practitioner {
        val decision = policyEvaluator.evaluate(
            principal = principal,
            request = PolicyEvaluationRequest(
                resourceType = PolicyResourceType.PRACTITIONER,
                operation = PolicyOperation.READ,
                organizationId = principal.organization.organizationId,
            ),
        )
        if (!decision.allowed) {
            auditEventService.recordDeniedAccess(decision, resourceId = practitionerId.value)
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Not authorized to read practitioners")
        }

        val practitioner = practitionerRepository.findByIdInOrganization(
            TenantScope(principal.organization.organizationId),
            practitionerId,
        )
        if (practitioner == null) {
            auditEventService.recordResourceAccess(
                decision = decision,
                operation = AuditOperation.READ,
                outcome = AuditOutcome.FAILURE,
                resourceId = practitionerId.value,
            )
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Practitioner not found")
        }

        auditEventService.recordResourceAccess(
            decision = decision,
            operation = AuditOperation.READ,
            outcome = AuditOutcome.SUCCESS,
            resourceId = practitioner.id.value,
        )
        return practitioner
    }
}
