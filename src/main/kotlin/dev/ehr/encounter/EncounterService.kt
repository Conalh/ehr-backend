package dev.ehr.encounter

import dev.ehr.identity.TenantScope
import dev.ehr.patient.PatientId
import dev.ehr.patient.PatientRepository
import dev.ehr.provenance.ProvenanceRecorder
import dev.ehr.security.AuditEventService
import dev.ehr.security.AuditOperation
import dev.ehr.security.AuditOutcome
import dev.ehr.security.PolicyDecision
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
class EncounterService(
    private val policyEvaluator: PolicyEvaluator,
    private val auditEventService: AuditEventService,
    private val encounterRepository: EncounterRepository,
    private val patientRepository: PatientRepository,
    private val provenanceRecorder: ProvenanceRecorder,
    private val transactionTemplate: TransactionTemplate,
) {
    fun open(
        principal: SecurityPrincipal,
        patientId: PatientId,
        command: EncounterCreateCommand,
    ): Encounter {
        val decision = evaluate(principal, PolicyOperation.WRITE)
        if (!decision.allowed) {
            auditEventService.recordDeniedAccess(decision, patientId = patientId.value)
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Not authorized to open encounters")
        }
        if (command.status != EncounterStatus.PLANNED && command.status != EncounterStatus.IN_PROGRESS) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Encounters can only be opened as planned or in-progress",
            )
        }

        try {
            return transactionTemplate.execute {
                val encounter = encounterRepository.create(command)
                provenanceRecorder.recordCreated(
                    principal = principal,
                    patientId = encounter.patientId.value,
                    targetResourceType = "ENCOUNTER",
                    targetResourceId = encounter.id.value,
                )
                auditEventService.recordResourceAccess(
                    decision = decision,
                    operation = AuditOperation.CREATE,
                    outcome = AuditOutcome.SUCCESS,
                    patientId = encounter.patientId.value,
                    resourceId = encounter.id.value,
                )
                encounter
            }!!
        } catch (exception: IllegalArgumentException) {
            // The same-organization insert found no patient row: not visible in this tenant.
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Patient not found")
        } catch (exception: DataIntegrityViolationException) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Encounter class concept is unknown")
        }
    }

    fun get(
        principal: SecurityPrincipal,
        encounterId: EncounterId,
    ): Encounter {
        val decision = evaluate(principal, PolicyOperation.READ)
        if (!decision.allowed) {
            auditEventService.recordDeniedAccess(decision, resourceId = encounterId.value)
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Not authorized to read encounters")
        }

        val encounter = encounterRepository.findById(tenantScope(principal), encounterId)
        if (encounter == null) {
            auditEventService.recordResourceAccess(
                decision = decision,
                operation = AuditOperation.READ,
                outcome = AuditOutcome.FAILURE,
                resourceId = encounterId.value,
            )
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Encounter not found")
        }

        auditEventService.recordResourceAccess(
            decision = decision,
            operation = AuditOperation.READ,
            outcome = AuditOutcome.SUCCESS,
            patientId = encounter.patientId.value,
            resourceId = encounter.id.value,
        )
        return encounter
    }

    fun timeline(
        principal: SecurityPrincipal,
        patientId: PatientId,
    ): List<Encounter> {
        val decision = evaluate(principal, PolicyOperation.READ)
        if (!decision.allowed) {
            auditEventService.recordDeniedAccess(decision, patientId = patientId.value)
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Not authorized to read encounters")
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

        val encounters = encounterRepository.findByPatient(scope, patientId)
        auditEventService.recordResourceAccess(
            decision = decision,
            operation = AuditOperation.SEARCH,
            outcome = AuditOutcome.SUCCESS,
            patientId = patientId.value,
        )
        return encounters
    }

    fun transition(
        principal: SecurityPrincipal,
        encounterId: EncounterId,
        command: EncounterTransitionCommand,
    ): Encounter {
        val decision = evaluate(principal, PolicyOperation.WRITE)
        if (!decision.allowed) {
            auditEventService.recordDeniedAccess(decision, resourceId = encounterId.value)
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Not authorized to update encounters")
        }

        val scope = tenantScope(principal)
        try {
            return transactionTemplate.execute {
                val prior = encounterRepository.findById(scope, encounterId)
                    ?: throw EncounterNotFoundForTransition()
                val encounter = encounterRepository.transition(scope, encounterId, command)
                    ?: throw EncounterNotFoundForTransition()
                provenanceRecorder.recordUpdated(
                    principal = principal,
                    patientId = encounter.patientId.value,
                    targetResourceType = "ENCOUNTER",
                    targetResourceId = encounter.id.value,
                    newVersion = encounter.version,
                    priorVersion = prior.version,
                    priorState = prior,
                )
                auditEventService.recordResourceAccess(
                    decision = decision,
                    operation = AuditOperation.UPDATE,
                    outcome = AuditOutcome.SUCCESS,
                    patientId = encounter.patientId.value,
                    resourceId = encounter.id.value,
                )
                encounter
            }!!
        } catch (exception: EncounterNotFoundForTransition) {
            recordFailedTransition(decision, encounterId)
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Encounter not found")
        } catch (exception: IllegalArgumentException) {
            recordFailedTransition(decision, encounterId)
            throw ResponseStatusException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                exception.message ?: "Encounter transition is not allowed",
            )
        } catch (exception: StaleEncounterTransitionException) {
            recordFailedTransition(decision, encounterId)
            throw ResponseStatusException(HttpStatus.CONFLICT, "Encounter was modified concurrently")
        }
    }

    private fun recordFailedTransition(
        decision: PolicyDecision,
        encounterId: EncounterId,
    ) {
        auditEventService.recordResourceAccess(
            decision = decision,
            operation = AuditOperation.UPDATE,
            outcome = AuditOutcome.FAILURE,
            resourceId = encounterId.value,
        )
    }

    private fun evaluate(
        principal: SecurityPrincipal,
        operation: PolicyOperation,
    ) = policyEvaluator.evaluate(
        principal = principal,
        request = PolicyEvaluationRequest(
            resourceType = PolicyResourceType.ENCOUNTER,
            operation = operation,
            organizationId = principal.organization.organizationId,
        ),
    )

    private fun tenantScope(principal: SecurityPrincipal): TenantScope =
        TenantScope(principal.organization.organizationId)

    private class EncounterNotFoundForTransition : RuntimeException()
}
