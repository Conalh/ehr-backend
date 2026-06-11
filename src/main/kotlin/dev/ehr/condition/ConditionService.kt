package dev.ehr.condition

import dev.ehr.encounter.EncounterRepository
import dev.ehr.identity.TenantScope
import dev.ehr.patient.PatientId
import dev.ehr.patient.PatientRepository
import dev.ehr.provenance.ProvenanceActivity
import dev.ehr.provenance.ProvenanceRecorder
import dev.ehr.security.AuditEventService
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
class ConditionService(
    private val policyEvaluator: PolicyEvaluator,
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
        val decision = evaluate(principal, PolicyOperation.WRITE, command.patientId.value)
        if (!decision.allowed) {
            auditEventService.recordDeniedAccess(decision, patientId = command.patientId.value)
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Not authorized to record conditions")
        }
        if (command.onsetDate != null && command.abatementDate != null && command.abatementDate < command.onsetDate) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Abatement date must not be before onset date")
        }

        val scope = tenantScope(principal)
        if (command.encounterId != null && encounterRepository.findById(scope, command.encounterId) == null) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Encounter not found")
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
        val decision = evaluate(principal, PolicyOperation.WRITE)
        if (!decision.allowed) {
            auditEventService.recordDeniedAccess(decision, resourceId = conditionId.value)
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Not authorized to update conditions")
        }

        val scope = tenantScope(principal)
        try {
            return transactionTemplate.execute {
                val prior = conditionRepository.findById(scope, conditionId)
                    ?: throw ConditionNotFoundForUpdate()
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
                // Re-evaluate with the discovered patient so the audit row
                // carries the shadow relationship basis (H3 enforces here).
                val compartmentDecision = evaluate(principal, PolicyOperation.WRITE, prior.patientId.value)
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
        val decision = evaluate(principal, PolicyOperation.READ)
        if (!decision.allowed) {
            auditEventService.recordDeniedAccess(decision, resourceId = conditionId.value)
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Not authorized to read conditions")
        }

        val condition = conditionRepository.findById(tenantScope(principal), conditionId)
        if (condition == null) {
            auditEventService.recordResourceAccess(
                decision = decision,
                operation = AuditOperation.READ,
                outcome = AuditOutcome.FAILURE,
                resourceId = conditionId.value,
            )
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Condition not found")
        }

        // Re-evaluate with the discovered patient so the audit row carries
        // the shadow relationship basis (H3 enforces here).
        auditEventService.recordResourceAccess(
            decision = evaluate(principal, PolicyOperation.READ, condition.patientId.value),
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
        val decision = evaluate(principal, PolicyOperation.READ, patientId.value)
        if (!decision.allowed) {
            auditEventService.recordDeniedAccess(decision, patientId = patientId.value)
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Not authorized to read conditions")
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

        val conditions = conditionRepository.findByPatient(scope, patientId)
        auditEventService.recordResourceAccess(
            decision = decision,
            operation = AuditOperation.SEARCH,
            outcome = AuditOutcome.SUCCESS,
            patientId = patientId.value,
        )
        return conditions
    }

    private fun evaluate(
        principal: SecurityPrincipal,
        operation: PolicyOperation,
        patientId: java.util.UUID? = null,
    ) = policyEvaluator.evaluate(
        principal = principal,
        request = PolicyEvaluationRequest(
            resourceType = PolicyResourceType.CONDITION,
            operation = operation,
            organizationId = principal.organization.organizationId,
            patientId = patientId,
        ),
    )

    private fun tenantScope(principal: SecurityPrincipal): TenantScope =
        TenantScope(principal.organization.organizationId)
}
