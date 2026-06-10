package dev.ehr.fhir

import dev.ehr.observation.Observation
import dev.ehr.observation.ObservationStatus
import dev.ehr.observation.ObservationValue
import dev.ehr.terminology.CanonicalCodeSystems
import dev.ehr.terminology.CodeableConcept
import org.hl7.fhir.r4.model.Coding
import org.hl7.fhir.r4.model.DateTimeType
import org.hl7.fhir.r4.model.Quantity
import org.hl7.fhir.r4.model.Reference
import org.hl7.fhir.r4.model.StringType
import org.springframework.stereotype.Component
import java.util.Date
import ca.uhn.fhir.model.api.TemporalPrecisionEnum
import org.hl7.fhir.r4.model.CodeableConcept as FhirCodeableConcept
import org.hl7.fhir.r4.model.Observation as FhirObservation

@Component
class ObservationFhirMapper {
    fun toFhirObservation(
        observation: Observation,
        codeConcept: CodeableConcept,
        valueConcept: CodeableConcept?,
    ): FhirObservation {
        val fhirObservation = FhirObservation()

        fhirObservation.id = observation.id.value.toString()
        fhirObservation.meta.versionId = observation.version.toString()
        fhirObservation.meta.lastUpdated = Date.from(observation.updatedAt)
        fhirObservation.status = toFhirStatus(observation.status)
        fhirObservation.addCategory(
            FhirCodeableConcept().addCoding(
                Coding()
                    .setSystem(CanonicalCodeSystems.HL7_OBSERVATION_CATEGORY)
                    .setCode(observation.category.dbValue),
            ),
        )
        fhirObservation.code = toFhirConcept(codeConcept)
        fhirObservation.subject = Reference("Patient/${observation.patientId.value}")
        observation.encounterId?.let { encounterId ->
            fhirObservation.encounter = Reference("Encounter/${encounterId.value}")
        }
        fhirObservation.effective = DateTimeType(Date.from(observation.effectiveAt), TemporalPrecisionEnum.SECOND)
            .apply { setTimeZoneZulu(true) }
        fhirObservation.value = when (val value = observation.value) {
            is ObservationValue.Quantity -> Quantity()
                .setValue(value.value)
                .setUnit(value.unit)
                .setSystem(CanonicalCodeSystems.UCUM)
                .setCode(value.unit)
            is ObservationValue.Coded -> toFhirConcept(
                requireNotNull(valueConcept) { "coded observation value requires a resolved concept" },
            )
            is ObservationValue.Text -> StringType(value.value)
        }

        return fhirObservation
    }

    private fun toFhirStatus(status: ObservationStatus): FhirObservation.ObservationStatus =
        when (status) {
            ObservationStatus.PRELIMINARY -> FhirObservation.ObservationStatus.PRELIMINARY
            ObservationStatus.FINAL -> FhirObservation.ObservationStatus.FINAL
            ObservationStatus.AMENDED -> FhirObservation.ObservationStatus.AMENDED
            ObservationStatus.CANCELLED -> FhirObservation.ObservationStatus.CANCELLED
            ObservationStatus.ENTERED_IN_ERROR -> FhirObservation.ObservationStatus.ENTEREDINERROR
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
