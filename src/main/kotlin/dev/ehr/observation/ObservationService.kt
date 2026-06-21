package dev.ehr.observation

import dev.ehr.encounter.EncounterRepository
import dev.ehr.patient.PatientId
import dev.ehr.patient.PatientRepository
import dev.ehr.provenance.ProvenanceActivity
import dev.ehr.provenance.ProvenanceRecorder
import dev.ehr.security.AuditEventService
import dev.ehr.security.AccessAuthorizer
import dev.ehr.security.CompartmentDeniedException
import dev.ehr.security.PolicyDecision
import dev.ehr.security.AuditOperation
import dev.ehr.security.AuditOutcome
import dev.ehr.security.PolicyOperation
import dev.ehr.security.PolicyResourceType
import dev.ehr.security.SecurityPrincipal
import dev.ehr.security.tenantScope
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.server.ResponseStatusException

@Service
class ObservationService(
    private val accessAuthorizer: AccessAuthorizer,
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
        val decision = authorize(
            principal = principal,
            operation = PolicyOperation.WRITE,
            forbiddenMessage = "Not authorized to record observations",
            patientId = command.patientId.value,
        )

        val scope = principal.tenantScope()
        if (command.encounterId != null) {
            val encounter = encounterRepository.findById(scope, command.encounterId)
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Encounter not found")
            if (encounter.patientId != command.patientId) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Encounter does not belong to patient")
            }
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
        val decision = authorize(
            principal = principal,
            operation = PolicyOperation.WRITE,
            forbiddenMessage = "Not authorized to amend observations",
            resourceId = observationId.value,
        )

        val scope = principal.tenantScope()
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
        val decision = authorize(
            principal = principal,
            operation = PolicyOperation.READ,
            forbiddenMessage = "Not authorized to read observations",
            resourceId = observationId.value,
        )

        val observation = observationRepository.findById(principal.tenantScope(), observationId)
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
        val compartmentDecision = authorize(
            principal = principal,
            operation = PolicyOperation.READ,
            forbiddenMessage = "Not authorized to read observations",
            patientId = observation.patientId.value,
            resourceId = observation.id.value,
        )
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
        val decision = authorize(
            principal = principal,
            operation = PolicyOperation.READ,
            forbiddenMessage = "Not authorized to read observations",
            patientId = patientId.value,
        )

        val scope = principal.tenantScope()
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

    private fun authorize(
        principal: SecurityPrincipal,
        operation: PolicyOperation,
        patientId: java.util.UUID? = null,
        resourceId: java.util.UUID? = null,
        forbiddenMessage: String,
    ) = accessAuthorizer.authorize(
        principal = principal,
        resourceType = PolicyResourceType.OBSERVATION,
        operation = operation,
        forbiddenMessage = forbiddenMessage,
        patientId = patientId,
        resourceId = resourceId,
    )

    private fun evaluate(
        principal: SecurityPrincipal,
        operation: PolicyOperation,
        patientId: java.util.UUID? = null,
    ) = accessAuthorizer.evaluate(
        principal = principal,
        resourceType = PolicyResourceType.OBSERVATION,
        operation = operation,
        patientId = patientId,
    )

}
