package dev.ehr.allergy

import dev.ehr.encounter.EncounterRepository
import dev.ehr.identity.TenantScope
import dev.ehr.patient.PatientId
import dev.ehr.patient.PatientRepository
import dev.ehr.provenance.ProvenanceRecorder
import dev.ehr.security.AuditEventService
import dev.ehr.security.AuditOperation
import dev.ehr.security.AuditOutcome
import dev.ehr.security.ClinicalAccessAuthorizer
import dev.ehr.security.PolicyOperation
import dev.ehr.security.PolicyResourceType
import dev.ehr.security.SecurityPrincipal
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.server.ResponseStatusException

@Service
class AllergyService(
    private val clinicalAccessAuthorizer: ClinicalAccessAuthorizer,
    private val auditEventService: AuditEventService,
    private val allergyRepository: AllergyRepository,
    private val patientRepository: PatientRepository,
    private val encounterRepository: EncounterRepository,
    private val provenanceRecorder: ProvenanceRecorder,
    private val transactionTemplate: TransactionTemplate,
) {
    fun record(
        principal: SecurityPrincipal,
        command: AllergyCreateCommand,
    ): Allergy {
        val decision = authorize(
            principal = principal,
            operation = PolicyOperation.WRITE,
            forbiddenMessage = "Not authorized to record allergies",
            patientId = command.patientId.value,
        )

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
                val allergy = allergyRepository.create(command)
                provenanceRecorder.recordCreated(
                    principal = principal,
                    patientId = allergy.patientId.value,
                    targetResourceType = "ALLERGY",
                    targetResourceId = allergy.id.value,
                )
                auditEventService.recordResourceAccess(
                    decision = decision,
                    operation = AuditOperation.CREATE,
                    outcome = AuditOutcome.SUCCESS,
                    patientId = allergy.patientId.value,
                    resourceId = allergy.id.value,
                )
                allergy
            }!!
        } catch (exception: IllegalArgumentException) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Patient not found")
        } catch (exception: DataIntegrityViolationException) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Allergy code concept is unknown")
        }
    }

    fun get(
        principal: SecurityPrincipal,
        allergyId: AllergyId,
    ): Allergy {
        val decision = authorize(
            principal = principal,
            operation = PolicyOperation.READ,
            forbiddenMessage = "Not authorized to read allergies",
            resourceId = allergyId.value,
        )

        val allergy = allergyRepository.findById(tenantScope(principal), allergyId)
        if (allergy == null) {
            auditEventService.recordResourceAccess(
                decision = decision,
                operation = AuditOperation.READ,
                outcome = AuditOutcome.FAILURE,
                resourceId = allergyId.value,
            )
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Allergy not found")
        }

        // Re-evaluate with the discovered patient: in enforced organizations
        // a missing treatment relationship denies here.
        val compartmentDecision = authorize(
            principal = principal,
            operation = PolicyOperation.READ,
            forbiddenMessage = "Not authorized to read allergies",
            patientId = allergy.patientId.value,
            resourceId = allergy.id.value,
        )
        auditEventService.recordResourceAccess(
            decision = compartmentDecision,
            operation = AuditOperation.READ,
            outcome = AuditOutcome.SUCCESS,
            patientId = allergy.patientId.value,
            resourceId = allergy.id.value,
        )
        return allergy
    }

    fun allergyList(
        principal: SecurityPrincipal,
        patientId: PatientId,
    ): List<Allergy> {
        val decision = authorize(
            principal = principal,
            operation = PolicyOperation.READ,
            forbiddenMessage = "Not authorized to read allergies",
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

        val allergies = allergyRepository.findByPatient(scope, patientId)
        auditEventService.recordResourceAccess(
            decision = decision,
            operation = AuditOperation.SEARCH,
            outcome = AuditOutcome.SUCCESS,
            patientId = patientId.value,
        )
        return allergies
    }

    private fun authorize(
        principal: SecurityPrincipal,
        operation: PolicyOperation,
        patientId: java.util.UUID? = null,
        resourceId: java.util.UUID? = null,
        forbiddenMessage: String,
    ) = clinicalAccessAuthorizer.authorize(
        principal = principal,
        resourceType = PolicyResourceType.ALLERGY,
        operation = operation,
        forbiddenMessage = forbiddenMessage,
        patientId = patientId,
        resourceId = resourceId,
    )

    private fun tenantScope(principal: SecurityPrincipal): TenantScope =
        TenantScope(principal.organization.organizationId)
}
