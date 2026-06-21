package dev.ehr.medication

import dev.ehr.encounter.EncounterRepository
import dev.ehr.identity.TenantScope
import dev.ehr.patient.PatientId
import dev.ehr.patient.PatientRepository
import dev.ehr.provenance.ProvenanceRecorder
import dev.ehr.security.AuditEventService
import dev.ehr.security.AuditOperation
import dev.ehr.security.AuditOutcome
import dev.ehr.security.AccessAuthorizer
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
    private val accessAuthorizer: AccessAuthorizer,
    private val auditEventService: AuditEventService,
    private val medicationStatementRepository: MedicationStatementRepository,
    private val patientRepository: PatientRepository,
    private val encounterRepository: EncounterRepository,
    private val provenanceRecorder: ProvenanceRecorder,
    private val transactionTemplate: TransactionTemplate,
) {
    fun record(
        principal: SecurityPrincipal,
        command: MedicationStatementCreateCommand,
    ): MedicationStatement {
        val decision = authorize(
            principal = principal,
            operation = PolicyOperation.WRITE,
            forbiddenMessage = "Not authorized to record medication statements",
            patientId = command.patientId.value,
        )
        if (command.effectiveStart != null && command.effectiveEnd != null &&
            command.effectiveEnd < command.effectiveStart
        ) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Effective end must not be before effective start")
        }

        val scope = tenantScope(principal)
        if (command.encounterId != null) {
            val encounter = encounterRepository.findById(scope, command.encounterId)
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Encounter not found")
            if (encounter.patientId != command.patientId) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Encounter does not belong to patient")
            }
        }

        try {
            return transactionTemplate.execute {
                val statement = medicationStatementRepository.create(command)
                provenanceRecorder.recordCreated(
                    principal = principal,
                    patientId = statement.patientId.value,
                    targetResourceType = "MEDICATION",
                    targetResourceId = statement.id.value,
                )
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
        val decision = authorize(
            principal = principal,
            operation = PolicyOperation.READ,
            forbiddenMessage = "Not authorized to read medication statements",
            resourceId = medicationStatementId.value,
        )

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

        // Re-evaluate with the discovered patient: in enforced organizations
        // a missing treatment relationship denies here.
        val compartmentDecision = authorize(
            principal = principal,
            operation = PolicyOperation.READ,
            forbiddenMessage = "Not authorized to read medication statements",
            patientId = statement.patientId.value,
            resourceId = statement.id.value,
        )
        auditEventService.recordResourceAccess(
            decision = compartmentDecision,
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
        val decision = authorize(
            principal = principal,
            operation = PolicyOperation.READ,
            forbiddenMessage = "Not authorized to read medication statements",
            patientId = patientId.value,
        )

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

    private fun authorize(
        principal: SecurityPrincipal,
        operation: PolicyOperation,
        patientId: java.util.UUID? = null,
        resourceId: java.util.UUID? = null,
        forbiddenMessage: String,
    ) = accessAuthorizer.authorize(
        principal = principal,
        resourceType = PolicyResourceType.MEDICATION,
        operation = operation,
        forbiddenMessage = forbiddenMessage,
        patientId = patientId,
        resourceId = resourceId,
    )

    private fun tenantScope(principal: SecurityPrincipal): TenantScope =
        TenantScope(principal.organization.organizationId)
}
