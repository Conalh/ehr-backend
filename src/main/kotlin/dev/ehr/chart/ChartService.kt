package dev.ehr.chart

import dev.ehr.allergy.Allergy
import dev.ehr.allergy.AllergyRepository
import dev.ehr.condition.Condition
import dev.ehr.condition.ConditionRepository
import dev.ehr.encounter.Encounter
import dev.ehr.encounter.EncounterRepository
import dev.ehr.identity.TenantScope
import dev.ehr.medication.MedicationStatement
import dev.ehr.medication.MedicationStatementRepository
import dev.ehr.note.ClinicalNote
import dev.ehr.note.ClinicalNoteRepository
import dev.ehr.observation.Observation
import dev.ehr.observation.ObservationRepository
import dev.ehr.patient.PatientId
import dev.ehr.patient.PatientRepository
import dev.ehr.patient.PatientWithIdentifiers
import dev.ehr.security.AccessAuthorizer
import dev.ehr.security.AuditEventService
import dev.ehr.security.AuditOperation
import dev.ehr.security.AuditOutcome
import dev.ehr.security.PolicyOperation
import dev.ehr.security.PolicyResourceType
import dev.ehr.security.SecurityPrincipal
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException

data class PatientChart(
    val patient: PatientWithIdentifiers,
    val encounters: List<Encounter>,
    val conditions: List<Condition>,
    val allergies: List<Allergy>,
    val medicationStatements: List<MedicationStatement>,
    val observations: List<Observation>,
    val notes: List<ClinicalNote>,
)

@Service
class ChartService(
    private val accessAuthorizer: AccessAuthorizer,
    private val auditEventService: AuditEventService,
    private val patientRepository: PatientRepository,
    private val encounterRepository: EncounterRepository,
    private val conditionRepository: ConditionRepository,
    private val allergyRepository: AllergyRepository,
    private val medicationStatementRepository: MedicationStatementRepository,
    private val observationRepository: ObservationRepository,
    private val clinicalNoteRepository: ClinicalNoteRepository,
) {
    fun chart(
        principal: SecurityPrincipal,
        patientId: PatientId,
    ): PatientChart {
        val decision = accessAuthorizer.authorize(
            principal = principal,
            resourceType = PolicyResourceType.CHART,
            operation = PolicyOperation.READ,
            forbiddenMessage = "Not authorized to read patient charts",
            patientId = patientId.value,
        )

        val scope = TenantScope(principal.organization.organizationId)
        val patient = patientRepository.findById(scope, patientId)
        if (patient == null) {
            auditEventService.recordResourceAccess(
                decision = decision,
                operation = AuditOperation.READ,
                outcome = AuditOutcome.FAILURE,
                resourceId = patientId.value,
            )
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Patient not found")
        }

        val chart = PatientChart(
            patient = PatientWithIdentifiers(patient, patientRepository.findIdentifiers(scope, patient.id)),
            encounters = encounterRepository.findByPatient(scope, patientId),
            conditions = conditionRepository.findByPatient(scope, patientId),
            allergies = allergyRepository.findByPatient(scope, patientId),
            medicationStatements = medicationStatementRepository.findByPatient(scope, patientId),
            observations = observationRepository.findByPatient(scope, patientId),
            notes = clinicalNoteRepository.findByPatient(scope, patientId),
        )

        auditEventService.recordResourceAccess(
            decision = decision,
            operation = AuditOperation.READ,
            outcome = AuditOutcome.SUCCESS,
            patientId = patientId.value,
            resourceId = patientId.value,
        )
        return chart
    }
}
