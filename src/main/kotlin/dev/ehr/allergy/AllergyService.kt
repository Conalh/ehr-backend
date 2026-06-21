package dev.ehr.allergy

import dev.ehr.encounter.EncounterRepository
import dev.ehr.patient.PatientId
import dev.ehr.patient.PatientAccessGuard
import dev.ehr.provenance.ProvenanceRecorder
import dev.ehr.security.AuditEventService
import dev.ehr.security.AuditOperation
import dev.ehr.security.AccessAuthorizer
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
class AllergyService(
    private val accessAuthorizer: AccessAuthorizer,
    private val auditEventService: AuditEventService,
    private val allergyRepository: AllergyRepository,
    private val patientAccessGuard: PatientAccessGuard,
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
                val allergy = allergyRepository.create(command)
                provenanceRecorder.recordCreated(
                    principal = principal,
                    patientId = allergy.patientId.value,
                    targetResourceType = "ALLERGY",
                    targetResourceId = allergy.id.value,
                )
                auditEventService.recordSuccessfulAccess(
                    decision = decision,
                    operation = AuditOperation.CREATE,
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

        val allergy = allergyRepository.findById(principal.tenantScope(), allergyId)
        if (allergy == null) {
            auditEventService.recordFailedAccess(
                decision = decision,
                operation = AuditOperation.READ,
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
        auditEventService.recordSuccessfulAccess(
            decision = compartmentDecision,
            operation = AuditOperation.READ,
            patientId = allergy.patientId.value,
            resourceId = allergy.id.value,
        )
        return allergy
    }

    fun allergyList(
        principal: SecurityPrincipal,
        patientId: PatientId,
    ): List<Allergy> {
        val visibilityDecision = authorize(
            principal = principal,
            operation = PolicyOperation.READ,
            forbiddenMessage = "Not authorized to read allergies",
        )

        val scope = principal.tenantScope()
        patientAccessGuard.requirePatientForSearch(scope, patientId, visibilityDecision)
        val decision = authorize(
            principal = principal,
            operation = PolicyOperation.READ,
            forbiddenMessage = "Not authorized to read allergies",
            patientId = patientId.value,
        )

        val allergies = allergyRepository.findByPatient(scope, patientId)
        auditEventService.recordSuccessfulAccess(
            decision = decision,
            operation = AuditOperation.SEARCH,
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
    ) = accessAuthorizer.authorize(
        principal = principal,
        resourceType = PolicyResourceType.ALLERGY,
        operation = operation,
        forbiddenMessage = forbiddenMessage,
        patientId = patientId,
        resourceId = resourceId,
    )

}
