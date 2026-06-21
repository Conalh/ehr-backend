package dev.ehr.provenance

import dev.ehr.patient.PatientId
import dev.ehr.patient.PatientRepository
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
import java.util.UUID

@Service
class ProvenanceQueryService(
    private val accessAuthorizer: AccessAuthorizer,
    private val auditEventService: AuditEventService,
    private val provenanceRepository: ProvenanceRepository,
    private val patientRepository: PatientRepository,
) {
    fun get(
        principal: SecurityPrincipal,
        provenanceId: UUID,
    ): ProvenanceEvent {
        val decision = authorize(principal, resourceId = provenanceId)

        val event = provenanceRepository.findById(principal.tenantScope(), provenanceId)
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
        val compartmentDecision = authorize(
            principal = principal,
            patientId = event.patientId,
            resourceId = event.id,
        )
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
        val decision = authorize(principal, resourceId = targetResourceId)

        val events = provenanceRepository.findByTarget(principal.tenantScope(), targetResourceType, targetResourceId)
        // Re-evaluate with the discovered patient: in enforced organizations
        // a missing treatment relationship denies here.
        val patientId = events.firstOrNull()?.patientId
        val compartmentDecision = patientId
            ?.let {
                authorize(
                    principal = principal,
                    patientId = it,
                    resourceId = targetResourceId,
                )
            }
            ?: decision
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
        val decision = authorize(principal, patientId = patientId)

        val events = provenanceRepository.findByTargets(
            principal.tenantScope(),
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
        val decision = authorize(principal, patientId = patientId)

        val scope = principal.tenantScope()
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

    private fun authorize(
        principal: SecurityPrincipal,
        patientId: UUID? = null,
        resourceId: UUID? = null,
    ) = accessAuthorizer.authorize(
        principal = principal,
        resourceType = PolicyResourceType.PROVENANCE,
        operation = PolicyOperation.READ,
        forbiddenMessage = "Not authorized to read provenance",
        patientId = patientId,
        resourceId = resourceId,
    )

}
