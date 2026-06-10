package dev.ehr.provenance

import dev.ehr.identity.TenantScope
import dev.ehr.patient.PatientId
import dev.ehr.patient.PatientRepository
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
import java.util.UUID

@Service
class ProvenanceQueryService(
    private val policyEvaluator: PolicyEvaluator,
    private val auditEventService: AuditEventService,
    private val provenanceRepository: ProvenanceRepository,
    private val patientRepository: PatientRepository,
) {
    fun get(
        principal: SecurityPrincipal,
        provenanceId: UUID,
    ): ProvenanceEvent {
        val decision = evaluate(principal)
        if (!decision.allowed) {
            auditEventService.recordDeniedAccess(decision, resourceId = provenanceId)
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Not authorized to read provenance")
        }

        val event = provenanceRepository.findById(tenantScope(principal), provenanceId)
        if (event == null) {
            auditEventService.recordResourceAccess(
                decision = decision,
                operation = AuditOperation.READ,
                outcome = AuditOutcome.FAILURE,
                resourceId = provenanceId,
            )
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Provenance not found")
        }

        auditEventService.recordResourceAccess(
            decision = decision,
            operation = AuditOperation.READ,
            outcome = AuditOutcome.SUCCESS,
            patientId = event.patientId,
            resourceId = event.id,
        )
        return event
    }

    fun searchByTarget(
        principal: SecurityPrincipal,
        targetResourceType: String,
        targetResourceId: UUID,
    ): List<ProvenanceEvent> {
        val decision = evaluate(principal)
        if (!decision.allowed) {
            auditEventService.recordDeniedAccess(decision, resourceId = targetResourceId)
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Not authorized to read provenance")
        }

        val events = provenanceRepository.findByTarget(tenantScope(principal), targetResourceType, targetResourceId)
        auditEventService.recordResourceAccess(
            decision = decision,
            operation = AuditOperation.SEARCH,
            outcome = AuditOutcome.SUCCESS,
            patientId = events.firstOrNull()?.patientId,
            resourceId = targetResourceId,
        )
        return events
    }

    fun searchByPatient(
        principal: SecurityPrincipal,
        patientId: UUID,
    ): List<ProvenanceEvent> {
        val decision = evaluate(principal)
        if (!decision.allowed) {
            auditEventService.recordDeniedAccess(decision, patientId = patientId)
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Not authorized to read provenance")
        }

        val scope = tenantScope(principal)
        if (patientRepository.findById(scope, PatientId(patientId)) == null) {
            auditEventService.recordResourceAccess(
                decision = decision,
                operation = AuditOperation.SEARCH,
                outcome = AuditOutcome.FAILURE,
                resourceId = patientId,
            )
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Patient not found")
        }

        val events = provenanceRepository.findByPatient(scope, patientId)
        auditEventService.recordResourceAccess(
            decision = decision,
            operation = AuditOperation.SEARCH,
            outcome = AuditOutcome.SUCCESS,
            patientId = patientId,
        )
        return events
    }

    private fun evaluate(principal: SecurityPrincipal) =
        policyEvaluator.evaluate(
            principal = principal,
            request = PolicyEvaluationRequest(
                resourceType = PolicyResourceType.PROVENANCE,
                operation = PolicyOperation.READ,
                organizationId = principal.organization.organizationId,
            ),
        )

    private fun tenantScope(principal: SecurityPrincipal): TenantScope =
        TenantScope(principal.organization.organizationId)
}
