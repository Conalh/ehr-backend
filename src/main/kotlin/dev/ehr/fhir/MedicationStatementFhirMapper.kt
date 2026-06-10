package dev.ehr.fhir

import dev.ehr.medication.MedicationStatement
import dev.ehr.medication.MedicationStatementStatus
import dev.ehr.terminology.CodeableConcept
import org.hl7.fhir.r4.model.Coding
import org.hl7.fhir.r4.model.DateTimeType
import org.hl7.fhir.r4.model.Dosage
import org.hl7.fhir.r4.model.Period
import org.hl7.fhir.r4.model.Reference
import org.springframework.stereotype.Component
import java.util.Date
import org.hl7.fhir.r4.model.CodeableConcept as FhirCodeableConcept
import org.hl7.fhir.r4.model.MedicationStatement as FhirMedicationStatement

@Component
class MedicationStatementFhirMapper {
    fun toFhirMedicationStatement(
        statement: MedicationStatement,
        medicationConcept: CodeableConcept,
    ): FhirMedicationStatement {
        val fhirStatement = FhirMedicationStatement()

        fhirStatement.id = statement.id.value.toString()
        fhirStatement.meta.versionId = statement.version.toString()
        fhirStatement.meta.lastUpdated = Date.from(statement.updatedAt)
        fhirStatement.status = toFhirStatus(statement.status)
        fhirStatement.medication = toFhirConcept(medicationConcept)
        fhirStatement.subject = Reference("Patient/${statement.patientId.value}")
        statement.encounterId?.let { encounterId ->
            fhirStatement.context = Reference("Encounter/${encounterId.value}")
        }
        if (statement.effectiveStart != null || statement.effectiveEnd != null) {
            val period = Period()
            statement.effectiveStart?.let { period.startElement = DateTimeType(it.toString()) }
            statement.effectiveEnd?.let { period.endElement = DateTimeType(it.toString()) }
            fhirStatement.effective = period
        }
        statement.dosageText?.let { dosageText ->
            fhirStatement.addDosage(Dosage().setText(dosageText))
        }
        fhirStatement.dateAssertedElement = DateTimeType(Date.from(statement.recordedAt))
            .apply { setTimeZoneZulu(true) }

        return fhirStatement
    }

    private fun toFhirStatus(status: MedicationStatementStatus): FhirMedicationStatement.MedicationStatementStatus =
        when (status) {
            MedicationStatementStatus.ACTIVE -> FhirMedicationStatement.MedicationStatementStatus.ACTIVE
            MedicationStatementStatus.COMPLETED -> FhirMedicationStatement.MedicationStatementStatus.COMPLETED
            MedicationStatementStatus.STOPPED -> FhirMedicationStatement.MedicationStatementStatus.STOPPED
            MedicationStatementStatus.ON_HOLD -> FhirMedicationStatement.MedicationStatementStatus.ONHOLD
            MedicationStatementStatus.ENTERED_IN_ERROR -> FhirMedicationStatement.MedicationStatementStatus.ENTEREDINERROR
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
}
