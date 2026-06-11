package dev.ehr.observation

import dev.ehr.encounter.EncounterRepository
import dev.ehr.identity.TenantScope
import dev.ehr.patient.PatientId
import dev.ehr.patient.PatientRepository
import dev.ehr.provenance.ProvenanceActivity
import dev.ehr.provenance.ProvenanceRecorder
import dev.ehr.security.AuditEventService
import dev.ehr.security.CompartmentDeniedException
import dev.ehr.security.PolicyDecision
import dev.ehr.security.AuditOperation
import dev.ehr.security.AuditOutcome
import dev.ehr.security.PolicyEvaluationRequest
import dev.ehr.security.PolicyEvaluator
import dev.ehr.security.PolicyOperation
import dev.ehr.security.PolicyResourceType
import dev.ehr.security.SecurityPrincipal
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.server.ResponseStatusException

@Service
class ObservationService(
    private val policyEvaluator: PolicyEvaluator,
    private val auditEventService: AuditEventService,
    private val observationRepository: ObservationRepository,
    private val patientRepository: PatientRepository,
    private val encounterRepository: EncounterRepository,
    private val provenanceRecorder: ProvenanceRecorder,
    private val transactionTemplate: TransactionTemplate,
) {
    fun record(
        principal: SecurityPrincipal,
        command: ObservationCreateCommand,
    ): Observation {
        val decision = evaluate(principal, PolicyOperation.WRITE, command.patientId.value)
        if (!decision.allowed) {
            auditEventService.recordDeniedAccess(decision, patientId = command.patientId.value)
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Not authorized to record observations")
        }

        val scope = tenantScope(principal)
        if (command.encounterId != null && encounterRepository.findById(scope, command.encounterId) == null) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Encounter not found")
        }

        try {
            return transactionTemplate.execute {
                val observation = observationRepository.create(command)
                provenanceRecorder.recordCreated(
                    principal = principal,
                    patientId = observation.patientId.value,
                    targetResourceType = "OBSERVATION",
                    targetResourceId = observation.id.value,
                )
                auditEventService.recordResourceAccess(
                    decision = decision,
                    operation = AuditOperation.CREATE,
                    outcome = AuditOutcome.SUCCESS,
                    patientId = observation.patientId.value,
                    resourceId = observation.id.value,
                )
                observation
            }!!
        } catch (exception: IllegalArgumentException) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Patient not found")
        } catch (exception: DataIntegrityViolationException) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Observation concept is unknown")
        }
    }

    fun amend(
        principal: SecurityPrincipal,
        observationId: ObservationId,
        newValue: ObservationValue,
        expectedVersion: Int,
    ): Observation {
        val decision = evaluate(principal, PolicyOperation.WRITE)
        if (!decision.allowed) {
            auditEventService.recordDeniedAccess(decision, resourceId = observationId.value)
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Not authorized to amend observations")
        }

        val scope = tenantScope(principal)
        try {
            return transactionTemplate.execute {
                val prior = observationRepository.findById(scope, observationId)
                    ?: throw ObservationNotFoundForUpdate()
                // Re-evaluate with the discovered patient: in enforced
                // organizations a missing treatment relationship denies here,
                // before the mutation. Thrown past the transaction so the
                // denial audit row survives the rollback.
                val compartmentDecision = evaluate(principal, PolicyOperation.WRITE, prior.patientId.value)
                if (!compartmentDecision.allowed) {
                    throw CompartmentDeniedException(compartmentDecision, prior.patientId.value, observationId.value)
                }
                val updated = observationRepository.amend(
                    tenantScope = scope,
                    observationId = observationId,
                    newValue = newValue,
                    expectedVersion = expectedVersion,
                    updatedBy = principal.subject.userId,
                )
                provenanceRecorder.recordUpdated(
                    principal = principal,
                    patientId = prior.patientId.value,
                    targetResourceType = "OBSERVATION",
                    targetResourceId = observationId.value,
                    newVersion = updated.version,
                    priorVersion = prior.version,
                    priorState = prior,
                    activity = ProvenanceActivity.AMENDED,
                )
                auditEventService.recordResourceAccess(
                    decision = compartmentDecision,
                    operation = AuditOperation.UPDATE,
                    outcome = AuditOutcome.SUCCESS,
                    patientId = updated.patientId.value,
                    resourceId = updated.id.value,
                )
                updated
            }!!
        } catch (exception: CompartmentDeniedException) {
            auditEventService.recordDeniedAccess(
                exception.decision,
                patientId = exception.patientId,
                resourceId = exception.resourceId,
            )
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Not authorized to amend observations")
        } catch (exception: ObservationNotFoundForUpdate) {
            recordFailedUpdate(decision, observationId.value)
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Observation not found")
        } catch (exception: StaleObservationUpdateException) {
            recordFailedUpdate(decision, observationId.value)
            throw ResponseStatusException(HttpStatus.CONFLICT, "Observation was modified concurrently")
        } catch (exception: DataIntegrityViolationException) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Observation value is invalid")
        }
    }

    private fun recordFailedUpdate(
        decision: PolicyDecision,
        resourceId: java.util.UUID,
    ) {
        auditEventService.recordResourceAccess(
            decision = decision,
            operation = AuditOperation.UPDATE,
            outcome = AuditOutcome.FAILURE,
            resourceId = resourceId,
        )
    }

    private class ObservationNotFoundForUpdate : RuntimeException()

    fun get(
        principal: SecurityPrincipal,
        observationId: ObservationId,
    ): Observation {
        val decision = evaluate(principal, PolicyOperation.READ)
        if (!decision.allowed) {
            auditEventService.recordDeniedAccess(decision, resourceId = observationId.value)
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Not authorized to read observations")
        }

        val observation = observationRepository.findById(tenantScope(principal), observationId)
        if (observation == null) {
            auditEventService.recordResourceAccess(
                decision = decision,
                operation = AuditOperation.READ,
                outcome = AuditOutcome.FAILURE,
                resourceId = observationId.value,
            )
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Observation not found")
        }

        // Re-evaluate with the discovered patient: in enforced organizations
        // a missing treatment relationship denies here.
        val compartmentDecision = evaluate(principal, PolicyOperation.READ, observation.patientId.value)
        if (!compartmentDecision.allowed) {
            auditEventService.recordDeniedAccess(
                compartmentDecision,
                patientId = observation.patientId.value,
                resourceId = observation.id.value,
            )
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Not authorized to read observations")
        }
        auditEventService.recordResourceAccess(
            decision = compartmentDecision,
            operation = AuditOperation.READ,
            outcome = AuditOutcome.SUCCESS,
            patientId = observation.patientId.value,
            resourceId = observation.id.value,
        )
        return observation
    }

    fun listForPatient(
        principal: SecurityPrincipal,
        patientId: PatientId,
        category: ObservationCategory? = null,
    ): List<Observation> {
        val decision = evaluate(principal, PolicyOperation.READ, patientId.value)
        if (!decision.allowed) {
            auditEventService.recordDeniedAccess(decision, patientId = patientId.value)
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Not authorized to read observations")
        }

        val scope = tenantScope(principal)
        if (patientRepository.findById(scope, patientId) == null) {
            auditEventService.recordResourceAccess(
                decision = decision,
                operation = AuditOperation.SEARCH,
                outcome = AuditOutcome.FAILURE,
                resourceId = patientId.value,
            )
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Patient not found")
        }

        val observations = observationRepository.findByPatient(scope, patientId, category)
        auditEventService.recordResourceAccess(
            decision = decision,
            operation = AuditOperation.SEARCH,
            outcome = AuditOutcome.SUCCESS,
            patientId = patientId.value,
        )
        return observations
    }

    private fun evaluate(
        principal: SecurityPrincipal,
        operation: PolicyOperation,
        patientId: java.util.UUID? = null,
    ) = policyEvaluator.evaluate(
        principal = principal,
        request = PolicyEvaluationRequest(
            resourceType = PolicyResourceType.OBSERVATION,
            operation = operation,
            organizationId = principal.organization.organizationId,
            patientId = patientId,
        ),
    )

    private fun tenantScope(principal: SecurityPrincipal): TenantScope =
        TenantScope(principal.organization.organizationId)
}
