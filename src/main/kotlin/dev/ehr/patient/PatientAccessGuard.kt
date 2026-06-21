package dev.ehr.patient

import dev.ehr.identity.TenantScope
import dev.ehr.security.AuditEventService
import dev.ehr.security.AuditOperation
import dev.ehr.security.PolicyDecision
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException

@Service
class PatientAccessGuard(
    private val patientRepository: PatientRepository,
    private val auditEventService: AuditEventService,
) {
    fun requirePatientForSearch(
        tenantScope: TenantScope,
        patientId: PatientId,
        decision: PolicyDecision,
    ) {
        if (patientRepository.findById(tenantScope, patientId) != null) {
            return
        }

        auditEventService.recordFailedAccess(
            decision = decision,
            operation = AuditOperation.SEARCH,
            resourceId = patientId.value,
        )
        throw ResponseStatusException(HttpStatus.NOT_FOUND, "Patient not found")
    }
}
