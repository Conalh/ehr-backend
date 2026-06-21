package dev.ehr.patient

import dev.ehr.identity.TenantScope
import dev.ehr.security.AccessAuthorizer
import dev.ehr.security.AuditEventService
import dev.ehr.security.AuditOperation
import dev.ehr.security.AuditOutcome
import dev.ehr.security.PolicyOperation
import dev.ehr.security.PolicyResourceType
import dev.ehr.provenance.ProvenanceRecorder
import dev.ehr.security.SecurityPrincipal
import dev.ehr.security.launchBoundPatientId
import org.springframework.dao.DuplicateKeyException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

data class PatientWithIdentifiers(
    val patient: Patient,
    val identifiers: List<PatientIdentifier>,
)

@Service
class PatientService(
    private val accessAuthorizer: AccessAuthorizer,
    private val auditEventService: AuditEventService,
    private val patientRepository: PatientRepository,
    private val provenanceRecorder: ProvenanceRecorder,
    private val transactionTemplate: TransactionTemplate,
) {
    fun create(
        principal: SecurityPrincipal,
        command: PatientCreateCommand,
        identifierCommands: List<PatientIdentifierCreateCommand>,
    ): PatientWithIdentifiers {
        val decision = authorize(principal, PolicyOperation.WRITE, "Not authorized to create patients")
        identifierCommands.forEach(::validateIdentifierCommand)

        val tenantScope = tenantScope(principal)
        try {
            return transactionTemplate.execute {
                val patient = patientRepository.create(command)
                val identifiers = identifierCommands.map { identifierCommand ->
                    patientRepository.addIdentifier(tenantScope, patient.id, identifierCommand)
                }
                provenanceRecorder.recordCreated(
                    principal = principal,
                    patientId = patient.id.value,
                    targetResourceType = "PATIENT",
                    targetResourceId = patient.id.value,
                )
                auditEventService.recordResourceAccess(
                    decision = decision,
                    operation = AuditOperation.CREATE,
                    outcome = AuditOutcome.SUCCESS,
                    patientId = patient.id.value,
                    resourceId = patient.id.value,
                )
                PatientWithIdentifiers(patient, identifiers)
            }!!
        } catch (exception: DuplicateKeyException) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "Patient identifier already exists in this organization")
        }
    }

    fun get(
        principal: SecurityPrincipal,
        patientId: PatientId,
    ): PatientWithIdentifiers {
        val decision = evaluate(principal, PolicyOperation.READ, patientId.value)
        if (!decision.allowed) {
            auditEventService.recordDeniedAccess(decision, resourceId = patientId.value)
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Not authorized to read patients")
        }

        val tenantScope = tenantScope(principal)
        val patient = patientRepository.findById(tenantScope, patientId)
        if (patient == null) {
            // Existence is unconfirmed, so the requested UUID is recorded as the
            // resource only; patient_id stays null to keep compartment audit clean.
            auditEventService.recordResourceAccess(
                decision = decision,
                operation = AuditOperation.READ,
                outcome = AuditOutcome.FAILURE,
                resourceId = patientId.value,
            )
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Patient not found")
        }

        auditEventService.recordResourceAccess(
            decision = decision,
            operation = AuditOperation.READ,
            outcome = AuditOutcome.SUCCESS,
            patientId = patient.id.value,
            resourceId = patient.id.value,
        )
        return PatientWithIdentifiers(patient, patientRepository.findIdentifiers(tenantScope, patient.id))
    }

    fun searchByIdentifier(
        principal: SecurityPrincipal,
        system: String,
        value: String,
    ): List<PatientWithIdentifiers> =
        search(principal, patientId = null, identifierSystem = system, identifierValue = value)

    fun search(
        principal: SecurityPrincipal,
        patientId: PatientId?,
        identifierSystem: String?,
        identifierValue: String?,
    ): List<PatientWithIdentifiers> {
        val decision = authorize(principal, PolicyOperation.READ, "Not authorized to search patients")

        val tenantScope = tenantScope(principal)
        // A launch-bound principal only ever sees the launched patient,
        // even through search parameters.
        val launchBound = principal.launchBoundPatientId()
        val idMatch = patientId
            ?.let { patientRepository.findById(tenantScope, it) }
            ?.takeIf { launchBound == null || it.id.value == launchBound }
        val identifierMatch = if (identifierSystem != null && identifierValue != null) {
            patientRepository.findByIdentifier(tenantScope, identifierSystem, identifierValue)
                ?.takeIf { launchBound == null || it.id.value == launchBound }
        } else {
            null
        }
        val patient = when {
            patientId != null && identifierSystem != null -> idMatch?.takeIf { it.id == identifierMatch?.id }
            patientId != null -> idMatch
            identifierSystem != null -> identifierMatch
            else -> null
        }

        auditEventService.recordResourceAccess(
            decision = decision,
            operation = AuditOperation.SEARCH,
            outcome = AuditOutcome.SUCCESS,
            patientId = patient?.id?.value,
            resourceId = patient?.id?.value,
        )
        return patient
            ?.let { listOf(PatientWithIdentifiers(it, patientRepository.findIdentifiers(tenantScope, it.id))) }
            ?: emptyList()
    }
    private fun evaluate(
        principal: SecurityPrincipal,
        operation: PolicyOperation,
        patientId: UUID? = null,
    ) = accessAuthorizer.evaluate(
        principal = principal,
        resourceType = PolicyResourceType.PATIENT,
        operation = operation,
        patientId = patientId,
    )

    private fun authorize(
        principal: SecurityPrincipal,
        operation: PolicyOperation,
        forbiddenMessage: String,
    ) = accessAuthorizer.authorize(
        principal = principal,
        resourceType = PolicyResourceType.PATIENT,
        operation = operation,
        forbiddenMessage = forbiddenMessage,
    )

    private fun tenantScope(principal: SecurityPrincipal): TenantScope =
        TenantScope(principal.organization.organizationId)

    private fun validateIdentifierCommand(command: PatientIdentifierCreateCommand) {
        if (command.assignerText != null && command.assignerText.isBlank()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Identifier assigner text must not be blank")
        }
        if (command.periodStart != null && command.periodEnd != null && command.periodEnd < command.periodStart) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Identifier period end must not be before period start")
        }
    }
}
