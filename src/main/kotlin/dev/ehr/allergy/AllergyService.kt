package dev.ehr.allergy

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
class AllergyService(
    private val policyEvaluator: PolicyEvaluator,
    private val auditEventService: AuditEventService,
    private val allergyRepository: AllergyRepository,
    private val patientRepository: PatientRepository,
    private val encounterRepository: EncounterRepository,
    private val transactionTemplate: TransactionTemplate,
) {
    fun record(
        principal: SecurityPrincipal,
        command: AllergyCreateCommand,
    ): Allergy {
        val decision = evaluate(principal, PolicyOperation.WRITE)
        if (!decision.allowed) {
            auditEventService.recordDeniedAccess(decision, patientId = command.patientId.value)
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Not authorized to record allergies")
        }

        val scope = tenantScope(principal)
        if (command.encounterId != null && encounterRepository.findById(scope, command.encounterId) == null) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Encounter not found")
        }

        try {
            return transactionTemplate.execute {
                val allergy = allergyRepository.create(command)
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
        val decision = evaluate(principal, PolicyOperation.READ)
        if (!decision.allowed) {
            auditEventService.recordDeniedAccess(decision, resourceId = allergyId.value)
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Not authorized to read allergies")
        }

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

        auditEventService.recordResourceAccess(
            decision = decision,
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
        val decision = evaluate(principal, PolicyOperation.READ)
        if (!decision.allowed) {
            auditEventService.recordDeniedAccess(decision, patientId = patientId.value)
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Not authorized to read allergies")
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

        val allergies = allergyRepository.findByPatient(scope, patientId)
        auditEventService.recordResourceAccess(
            decision = decision,
            operation = AuditOperation.SEARCH,
            outcome = AuditOutcome.SUCCESS,
            patientId = patientId.value,
        )
        return allergies
    }

    private fun evaluate(
        principal: SecurityPrincipal,
        operation: PolicyOperation,
    ) = policyEvaluator.evaluate(
        principal = principal,
        request = PolicyEvaluationRequest(
            resourceType = PolicyResourceType.ALLERGY,
            operation = operation,
            organizationId = principal.organization.organizationId,
        ),
    )

    private fun tenantScope(principal: SecurityPrincipal): TenantScope =
        TenantScope(principal.organization.organizationId)
}
