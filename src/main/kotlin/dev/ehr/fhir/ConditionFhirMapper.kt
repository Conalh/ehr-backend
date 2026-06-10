package dev.ehr.fhir

import dev.ehr.condition.Condition
import dev.ehr.condition.ConditionClinicalStatus
import dev.ehr.condition.ConditionVerificationStatus
import dev.ehr.terminology.CanonicalCodeSystems
import dev.ehr.terminology.CodeableConcept
import org.hl7.fhir.r4.model.Coding
import org.hl7.fhir.r4.model.DateTimeType
import org.hl7.fhir.r4.model.Reference
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.util.Date
import org.hl7.fhir.r4.model.CodeableConcept as FhirCodeableConcept
import org.hl7.fhir.r4.model.Condition as FhirCondition

@Component
class ConditionFhirMapper {
    fun toFhirCondition(
        condition: Condition,
        codeConcept: CodeableConcept,
    ): FhirCondition {
        val fhirCondition = FhirCondition()

        fhirCondition.id = condition.id.value.toString()
        fhirCondition.meta.versionId = condition.version.toString()
        fhirCondition.meta.lastUpdated = Date.from(condition.updatedAt)
        fhirCondition.clinicalStatus = statusConcept(
            system = CanonicalCodeSystems.HL7_CONDITION_CLINICAL,
            code = condition.clinicalStatus.dbValue,
        )
        fhirCondition.verificationStatus = statusConcept(
            system = CanonicalCodeSystems.HL7_CONDITION_VER_STATUS,
            code = condition.verificationStatus.dbValue,
        )
        fhirCondition.code = toFhirConcept(codeConcept)
        fhirCondition.subject = Reference("Patient/${condition.patientId.value}")
        condition.encounterId?.let { encounterId ->
            fhirCondition.encounter = Reference("Encounter/${encounterId.value}")
        }
        condition.onsetDate?.let { fhirCondition.onset = dayDateTime(it) }
        condition.abatementDate?.let { fhirCondition.abatement = dayDateTime(it) }
        fhirCondition.recordedDateElement = DateTimeType(Date.from(condition.recordedAt))
            .apply { setTimeZoneZulu(true) }

        return fhirCondition
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

    private fun statusConcept(
        system: String,
        code: String,
    ): FhirCodeableConcept =
        FhirCodeableConcept().addCoding(
            Coding().setSystem(system).setCode(code),
        )

    private fun dayDateTime(date: LocalDate): DateTimeType =
        DateTimeType(date.toString())
}
