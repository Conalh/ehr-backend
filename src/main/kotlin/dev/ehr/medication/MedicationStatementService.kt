package dev.ehr.medication

import dev.ehr.encounter.EncounterRepository
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
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.server.ResponseStatusException

@Service
class MedicationStatementService(
    private val policyEvaluator: PolicyEvaluator,
    private val auditEventService: AuditEventService,
    private val medicationStatementRepository: MedicationStatementRepository,
    private val patientRepository: PatientRepository,
    private val encounterRepository: EncounterRepository,
    private val transactionTemplate: TransactionTemplate,
) {
    fun record(
        principal: SecurityPrincipal,
        command: MedicationStatementCreateCommand,
    ): MedicationStatement {
        val decision = evaluate(principal, PolicyOperation.WRITE)
        if (!decision.allowed) {
            auditEventService.recordDeniedAccess(decision, patientId = command.patientId.value)
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Not authorized to record medication statements")
        }
        if (command.effectiveStart != null && command.effectiveEnd != null &&
            command.effectiveEnd < command.effectiveStart
        ) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Effective end must not be before effective start")
        }

        val scope = tenantScope(principal)
        if (command.encounterId != null && encounterRepository.findById(scope, command.encounterId) == null) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Encounter not found")
        }

        try {
            return transactionTemplate.execute {
                val statement = medicationStatementRepository.create(command)
                auditEventService.recordResourceAccess(
                    decision = decision,
                    operation = AuditOperation.CREATE,
                    outcome = AuditOutcome.SUCCESS,
                    patientId = statement.patientId.value,
                    resourceId = statement.id.value,
                )
                statement
            }!!
        } catch (exception: IllegalArgumentException) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Patient not found")
        } catch (exception: DataIntegrityViolationException) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Medication concept is unknown")
        }
    }

    fun get(
        principal: SecurityPrincipal,
        medicationStatementId: MedicationStatementId,
    ): MedicationStatement {
        val decision = evaluate(principal, PolicyOperation.READ)
        if (!decision.allowed) {
            auditEventService.recordDeniedAccess(decision, resourceId = medicationStatementId.value)
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Not authorized to read medication statements")
        }

        val statement = medicationStatementRepository.findById(tenantScope(principal), medicationStatementId)
        if (statement == null) {
            auditEventService.recordResourceAccess(
                decision = decision,
                operation = AuditOperation.READ,
                outcome = AuditOutcome.FAILURE,
                resourceId = medicationStatementId.value,
            )
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Medication statement not found")
        }

        auditEventService.recordResourceAccess(
            decision = decision,
            operation = AuditOperation.READ,
            outcome = AuditOutcome.SUCCESS,
            patientId = statement.patientId.value,
            resourceId = statement.id.value,
        )
        return statement
    }

    fun listForPatient(
        principal: SecurityPrincipal,
        patientId: PatientId,
    ): List<MedicationStatement> {
        val decision = evaluate(principal, PolicyOperation.READ)
        if (!decision.allowed) {
            auditEventService.recordDeniedAccess(decision, patientId = patientId.value)
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Not authorized to read medication statements")
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

        val statements = medicationStatementRepository.findByPatient(scope, patientId)
        auditEventService.recordResourceAccess(
            decision = decision,
            operation = AuditOperation.SEARCH,
            outcome = AuditOutcome.SUCCESS,
            patientId = patientId.value,
        )
        return statements
    }

    private fun evaluate(
        principal: SecurityPrincipal,
        operation: PolicyOperation,
    ) = policyEvaluator.evaluate(
        principal = principal,
        request = PolicyEvaluationRequest(
            resourceType = PolicyResourceType.MEDICATION,
            operation = operation,
            organizationId = principal.organization.organizationId,
        ),
    )

    private fun tenantScope(principal: SecurityPrincipal): TenantScope =
        TenantScope(principal.organization.organizationId)
}
