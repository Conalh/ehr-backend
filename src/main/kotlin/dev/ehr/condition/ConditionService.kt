package dev.ehr.condition

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
class ConditionService(
    private val accessAuthorizer: AccessAuthorizer,
    private val auditEventService: AuditEventService,
    private val conditionRepository: ConditionRepository,
    private val patientRepository: PatientRepository,
    private val encounterRepository: EncounterRepository,
    private val provenanceRecorder: ProvenanceRecorder,
    private val transactionTemplate: TransactionTemplate,
) {
    fun record(
        principal: SecurityPrincipal,
        command: ConditionCreateCommand,
    ): Condition {
        val decision = authorize(
            principal = principal,
            operation = PolicyOperation.WRITE,
            forbiddenMessage = "Not authorized to record conditions",
            patientId = command.patientId.value,
        )
        if (command.onsetDate != null && command.abatementDate != null && command.abatementDate < command.onsetDate) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Abatement date must not be before onset date")
        }

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
                val condition = conditionRepository.create(command)
                provenanceRecorder.recordCreated(
                    principal = principal,
                    patientId = condition.patientId.value,
                    targetResourceType = "CONDITION",
                    targetResourceId = condition.id.value,
                )
                auditEventService.recordResourceAccess(
                    decision = decision,
                    operation = AuditOperation.CREATE,
                    outcome = AuditOutcome.SUCCESS,
                    patientId = condition.patientId.value,
                    resourceId = condition.id.value,
                )
                condition
            }!!
        } catch (exception: IllegalArgumentException) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Patient not found")
        } catch (exception: DataIntegrityViolationException) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Condition code concept is unknown")
        }
    }

    fun update(
        principal: SecurityPrincipal,
        conditionId: ConditionId,
        clinicalStatus: ConditionClinicalStatus?,
        verificationStatus: ConditionVerificationStatus?,
        onsetDate: java.time.LocalDate?,
        abatementDate: java.time.LocalDate?,
        expectedVersion: Int,
    ): Condition {
        val decision = authorize(
            principal = principal,
            operation = PolicyOperation.WRITE,
            forbiddenMessage = "Not authorized to update conditions",
            resourceId = conditionId.value,
        )

        val scope = principal.tenantScope()
        try {
            return transactionTemplate.execute {
                val prior = conditionRepository.findById(scope, conditionId)
                    ?: throw ConditionNotFoundForUpdate()
                // Re-evaluate with the discovered patient: in enforced
                // organizations a missing treatment relationship denies here,
                // before the mutation. Thrown past the transaction so the
                // denial audit row survives the rollback.
                val compartmentDecision = evaluate(principal, PolicyOperation.WRITE, prior.patientId.value)
                if (!compartmentDecision.allowed) {
                    throw CompartmentDeniedException(compartmentDecision, prior.patientId.value, conditionId.value)
                }
                val newClinicalStatus = clinicalStatus ?: prior.clinicalStatus
                val newVerificationStatus = verificationStatus ?: prior.verificationStatus
                val newOnsetDate = onsetDate ?: prior.onsetDate
                val newAbatementDate = abatementDate ?: prior.abatementDate
                if (newOnsetDate != null && newAbatementDate != null && newAbatementDate < newOnsetDate) {
                    throw ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Abatement date must not be before onset date",
                    )
                }
                val updated = conditionRepository.update(
                    tenantScope = scope,
                    conditionId = conditionId,
                    clinicalStatus = newClinicalStatus,
                    verificationStatus = newVerificationStatus,
                    onsetDate = newOnsetDate,
                    abatementDate = newAbatementDate,
                    expectedVersion = expectedVersion,
                    updatedBy = principal.subject.userId,
                )
                val voided = newVerificationStatus == ConditionVerificationStatus.ENTERED_IN_ERROR &&
                    prior.verificationStatus != ConditionVerificationStatus.ENTERED_IN_ERROR
                provenanceRecorder.recordUpdated(
                    principal = principal,
                    patientId = prior.patientId.value,
                    targetResourceType = "CONDITION",
                    targetResourceId = conditionId.value,
                    newVersion = updated.version,
                    priorVersion = prior.version,
                    priorState = prior,
                    activity = if (voided) ProvenanceActivity.ENTERED_IN_ERROR else ProvenanceActivity.UPDATED,
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
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Not authorized to update conditions")
        } catch (exception: ConditionNotFoundForUpdate) {
            recordFailedUpdate(decision, conditionId.value)
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Condition not found")
        } catch (exception: StaleConditionUpdateException) {
            recordFailedUpdate(decision, conditionId.value)
            throw ResponseStatusException(HttpStatus.CONFLICT, "Condition was modified concurrently")
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

    private class ConditionNotFoundForUpdate : RuntimeException()

    fun get(
        principal: SecurityPrincipal,
        conditionId: ConditionId,
    ): Condition {
        val decision = authorize(
            principal = principal,
            operation = PolicyOperation.READ,
            forbiddenMessage = "Not authorized to read conditions",
            resourceId = conditionId.value,
        )

        val condition = conditionRepository.findById(principal.tenantScope(), conditionId)
        if (condition == null) {
            auditEventService.recordResourceAccess(
                decision = decision,
                operation = AuditOperation.READ,
                outcome = AuditOutcome.FAILURE,
                resourceId = conditionId.value,
            )
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Condition not found")
        }

        // Re-evaluate with the discovered patient: in enforced organizations
        // a missing treatment relationship denies here.
        val compartmentDecision = authorize(
            principal = principal,
            operation = PolicyOperation.READ,
            forbiddenMessage = "Not authorized to read conditions",
            patientId = condition.patientId.value,
            resourceId = condition.id.value,
        )
        auditEventService.recordResourceAccess(
            decision = compartmentDecision,
            operation = AuditOperation.READ,
            outcome = AuditOutcome.SUCCESS,
            patientId = condition.patientId.value,
            resourceId = condition.id.value,
        )
        return condition
    }

    fun problemList(
        principal: SecurityPrincipal,
        patientId: PatientId,
    ): List<Condition> {
        val decision = authorize(
            principal = principal,
            operation = PolicyOperation.READ,
            forbiddenMessage = "Not authorized to read conditions",
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

        val conditions = conditionRepository.findByPatient(scope, patientId)
        auditEventService.recordResourceAccess(
            decision = decision,
            operation = AuditOperation.SEARCH,
            outcome = AuditOutcome.SUCCESS,
            patientId = patientId.value,
        )
        return conditions
    }

    private fun authorize(
        principal: SecurityPrincipal,
        operation: PolicyOperation,
        patientId: java.util.UUID? = null,
        resourceId: java.util.UUID? = null,
        forbiddenMessage: String,
    ) = accessAuthorizer.authorize(
        principal = principal,
        resourceType = PolicyResourceType.CONDITION,
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
        resourceType = PolicyResourceType.CONDITION,
        operation = operation,
        patientId = patientId,
    )

}
