package dev.ehr.chart

import dev.ehr.allergy.AllergyResponse
import dev.ehr.allergy.toResponse
import dev.ehr.condition.ConditionResponse
import dev.ehr.condition.toResponse
import dev.ehr.encounter.EncounterResponse
import dev.ehr.encounter.toResponse
import dev.ehr.medication.MedicationStatementResponse
import dev.ehr.medication.toResponse
import dev.ehr.note.ClinicalNoteResponse
import dev.ehr.note.toResponse
import dev.ehr.observation.ObservationResponse
import dev.ehr.observation.toResponse
import dev.ehr.patient.PatientId
import dev.ehr.patient.PatientResponse
import dev.ehr.patient.toResponse
import dev.ehr.security.securityPrincipal
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/v1")
class ChartController(
    private val chartService: ChartService,
) {
    @GetMapping("/patients/{patientId}/chart")
    fun chart(
        authentication: Authentication,
        @PathVariable patientId: UUID,
    ): PatientChartResponse {
        val principal = authentication.securityPrincipal()
        val chart = chartService.chart(principal, PatientId(patientId))
        return PatientChartResponse(
            patient = chart.patient.toResponse(),
            encounters = chart.encounters.map { it.toResponse() },
            conditions = chart.conditions.map { it.toResponse() },
            allergies = chart.allergies.map { it.toResponse() },
            medicationStatements = chart.medicationStatements.map { it.toResponse() },
            observations = chart.observations.map { it.toResponse() },
            notes = chart.notes.map { it.toResponse() },
        )
    }
}

data class PatientChartResponse(
    val patient: PatientResponse,
    val encounters: List<EncounterResponse>,
    val conditions: List<ConditionResponse>,
    val allergies: List<AllergyResponse>,
    val medicationStatements: List<MedicationStatementResponse>,
    val observations: List<ObservationResponse>,
    val notes: List<ClinicalNoteResponse>,
)
