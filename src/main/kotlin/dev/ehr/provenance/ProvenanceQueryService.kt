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

        // Re-evaluate with the discovered patient: in enforced organizations
        // a missing treatment relationship denies here.
        val compartmentDecision = evaluate(principal, event.patientId)
        if (!compartmentDecision.allowed) {
            auditEventService.recordDeniedAccess(
                compartmentDecision,
                patientId = event.patientId,
                resourceId = event.id,
            )
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Not authorized to read provenance")
        }
        auditEventService.recordResourceAccess(
            decision = compartmentDecision,
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
        // Re-evaluate with the discovered patient: in enforced organizations
        // a missing treatment relationship denies here.
        val patientId = events.firstOrNull()?.patientId
        val compartmentDecision = if (patientId != null) evaluate(principal, patientId) else decision
        if (!compartmentDecision.allowed) {
            auditEventService.recordDeniedAccess(
                compartmentDecision,
                patientId = patientId,
                resourceId = targetResourceId,
            )
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Not authorized to read provenance")
        }
        auditEventService.recordResourceAccess(
            decision = compartmentDecision,
            operation = AuditOperation.SEARCH,
            outcome = AuditOutcome.SUCCESS,
            patientId = patientId,
            resourceId = targetResourceId,
        )
        return events
    }

    /**
     * Batch lookup backing _revinclude=Provenance:target: one policy
     * evaluation carrying the compartment patient, one SEARCH audit row —
     * the included provenance rides the same authorization as the matches
     * it annotates.
     */
    fun searchByTargets(
        principal: SecurityPrincipal,
        patientId: UUID,
        targetResourceType: String,
        targetResourceIds: List<UUID>,
    ): List<ProvenanceEvent> {
        val decision = evaluate(principal, patientId)
        if (!decision.allowed) {
            auditEventService.recordDeniedAccess(decision, patientId = patientId)
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Not authorized to read provenance")
        }

        val events = provenanceRepository.findByTargets(
            tenantScope(principal),
            targetResourceType,
            targetResourceIds,
        )
        auditEventService.recordResourceAccess(
            decision = decision,
            operation = AuditOperation.SEARCH,
            outcome = AuditOutcome.SUCCESS,
            patientId = patientId,
        )
        return events
    }

    fun searchByPatient(
        principal: SecurityPrincipal,
        patientId: UUID,
    ): List<ProvenanceEvent> {
        val decision = evaluate(principal, patientId)
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

    private fun evaluate(
        principal: SecurityPrincipal,
        patientId: UUID? = null,
    ) = policyEvaluator.evaluate(
        principal = principal,
        request = PolicyEvaluationRequest(
            resourceType = PolicyResourceType.PROVENANCE,
            operation = PolicyOperation.READ,
            organizationId = principal.organization.organizationId,
            patientId = patientId,
        ),
    )

    private fun tenantScope(principal: SecurityPrincipal): TenantScope =
        TenantScope(principal.organization.organizationId)
}
