package dev.ehr.fhir

import dev.ehr.diagnostics.DiagnosticReport
import dev.ehr.diagnostics.DiagnosticReportStatus
import dev.ehr.terminology.CodeableConcept
import org.hl7.fhir.r4.model.Coding
import org.hl7.fhir.r4.model.InstantType
import org.hl7.fhir.r4.model.Reference
import org.springframework.stereotype.Component
import java.util.Date
import org.hl7.fhir.r4.model.CodeableConcept as FhirCodeableConcept
import org.hl7.fhir.r4.model.DiagnosticReport as FhirDiagnosticReport

@Component
class DiagnosticReportFhirMapper {
    fun toFhirDiagnosticReport(
        report: DiagnosticReport,
        codeConcept: CodeableConcept,
    ): FhirDiagnosticReport {
        val fhirReport = FhirDiagnosticReport()

        fhirReport.id = report.id.value.toString()
        fhirReport.meta.versionId = report.version.toString()
        fhirReport.meta.lastUpdated = Date.from(report.updatedAt)
        fhirReport.meta.addProfile(US_CORE_DIAGNOSTICREPORT_LAB_PROFILE)
        fhirReport.status = toFhirStatus(report.status)
        // This model only produces order-result (laboratory) reports.
        fhirReport.addCategory(
            FhirCodeableConcept().addCoding(
                Coding()
                    .setSystem(V2_0074_SYSTEM)
                    .setCode("LAB"),
            ),
        )
        fhirReport.code = toFhirConcept(codeConcept)
        fhirReport.subject = Reference("Patient/${report.patientId.value}")
        report.encounterId?.let { encounterId ->
            fhirReport.encounter = Reference("Encounter/${encounterId.value}")
        }
        report.resultObservationIds.forEach { observationId ->
            fhirReport.addResult(Reference("Observation/${observationId.value}"))
        }
        report.conclusionText?.let(fhirReport::setConclusion)
        fhirReport.issuedElement = InstantType(Date.from(report.issuedAt))
            .apply { setTimeZoneZulu(true) }
        // US Core lab reports require effective[x]; specimen-collection time
        // is not modeled, so the issue time is the honest approximation.
        fhirReport.effective = org.hl7.fhir.r4.model.DateTimeType(
            Date.from(report.issuedAt),
            ca.uhn.fhir.model.api.TemporalPrecisionEnum.SECOND,
        ).apply { setTimeZoneZulu(true) }

        return fhirReport
    }

    private fun toFhirStatus(status: DiagnosticReportStatus): FhirDiagnosticReport.DiagnosticReportStatus =
        when (status) {
            DiagnosticReportStatus.PARTIAL -> FhirDiagnosticReport.DiagnosticReportStatus.PARTIAL
            DiagnosticReportStatus.FINAL -> FhirDiagnosticReport.DiagnosticReportStatus.FINAL
            DiagnosticReportStatus.AMENDED -> FhirDiagnosticReport.DiagnosticReportStatus.AMENDED
            DiagnosticReportStatus.ENTERED_IN_ERROR -> FhirDiagnosticReport.DiagnosticReportStatus.ENTEREDINERROR
        }

    private fun toFhirConcept(concept: CodeableConcept): FhirCodeableConcept {
        val fhirConcept = FhirCodeableConcept()
        concept.codings.forEach { coding ->
            fhirConcept.addCoding(
                Coding()
                    .setSystem(coding.system)
                    .setCode(coding.code)
                    .setDisplay(coding.display),
            )
        }
        concept.text?.let(fhirConcept::setText)
        return fhirConcept
    }

    companion object {
        const val US_CORE_DIAGNOSTICREPORT_LAB_PROFILE =
            "http://hl7.org/fhir/us/core/StructureDefinition/us-core-diagnosticreport-lab"
        const val V2_0074_SYSTEM = "http://terminology.hl7.org/CodeSystem/v2-0074"
    }
}
