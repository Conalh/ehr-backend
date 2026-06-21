package dev.ehr.practitioner

import dev.ehr.identity.Practitioner
import dev.ehr.identity.PractitionerId
import dev.ehr.identity.PractitionerRepository
import dev.ehr.security.AccessAuthorizer
import dev.ehr.security.AuditEventService
import dev.ehr.security.AuditOperation
import dev.ehr.security.AuditOutcome
import dev.ehr.security.PolicyOperation
import dev.ehr.security.PolicyResourceType
import dev.ehr.security.SecurityPrincipal
import dev.ehr.security.tenantScope
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException

@Service
class PractitionerService(
    private val accessAuthorizer: AccessAuthorizer,
    private val auditEventService: AuditEventService,
    private val practitionerRepository: PractitionerRepository,
) {
    fun get(
        principal: SecurityPrincipal,
        practitionerId: PractitionerId,
    ): Practitioner {
        val decision = accessAuthorizer.authorize(
            principal = principal,
            resourceType = PolicyResourceType.PRACTITIONER,
            operation = PolicyOperation.READ,
            forbiddenMessage = "Not authorized to read practitioners",
            resourceId = practitionerId.value,
        )

        val practitioner = practitionerRepository.findByIdInOrganization(
            principal.tenantScope(),
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
