package dev.ehr.fhir

import dev.ehr.allergy.Allergy
import dev.ehr.allergy.AllergyCategory
import dev.ehr.allergy.AllergyCriticality
import dev.ehr.terminology.CanonicalCodeSystems
import dev.ehr.terminology.CodeableConcept
import org.hl7.fhir.r4.model.AllergyIntolerance
import org.hl7.fhir.r4.model.Coding
import org.hl7.fhir.r4.model.DateTimeType
import org.hl7.fhir.r4.model.Reference
import org.springframework.stereotype.Component
import java.util.Date
import org.hl7.fhir.r4.model.CodeableConcept as FhirCodeableConcept

@Component
class AllergyFhirMapper {
    fun toFhirAllergyIntolerance(
        allergy: Allergy,
        codeConcept: CodeableConcept,
    ): AllergyIntolerance {
        val fhirAllergy = AllergyIntolerance()

        fhirAllergy.id = allergy.id.value.toString()
        fhirAllergy.meta.versionId = allergy.version.toString()
        fhirAllergy.meta.lastUpdated = Date.from(allergy.updatedAt)
        fhirAllergy.meta.addProfile("http://hl7.org/fhir/us/core/StructureDefinition/us-core-allergyintolerance")
        fhirAllergy.clinicalStatus = statusConcept(
            system = CanonicalCodeSystems.HL7_ALLERGY_CLINICAL,
            code = allergy.clinicalStatus.dbValue,
        )
        fhirAllergy.verificationStatus = statusConcept(
            system = CanonicalCodeSystems.HL7_ALLERGY_VER_STATUS,
            code = allergy.verificationStatus.dbValue,
        )
        fhirAllergy.code = toFhirConcept(codeConcept)
        fhirAllergy.patient = Reference("Patient/${allergy.patientId.value}")
        allergy.encounterId?.let { encounterId ->
            fhirAllergy.encounter = Reference("Encounter/${encounterId.value}")
        }
        allergy.category?.let { category ->
            fhirAllergy.addCategory(toFhirCategory(category))
        }
        allergy.criticality?.let { criticality ->
            fhirAllergy.criticality = toFhirCriticality(criticality)
        }
        allergy.onsetDate?.let { onsetDate ->
            fhirAllergy.onset = DateTimeType(onsetDate.toString())
        }
        fhirAllergy.recordedDateElement = DateTimeType(Date.from(allergy.recordedAt))
            .apply { setTimeZoneZulu(true) }

        return fhirAllergy
    }

    private fun toFhirCategory(category: AllergyCategory): AllergyIntolerance.AllergyIntoleranceCategory =
        when (category) {
            AllergyCategory.FOOD -> AllergyIntolerance.AllergyIntoleranceCategory.FOOD
            AllergyCategory.MEDICATION -> AllergyIntolerance.AllergyIntoleranceCategory.MEDICATION
            AllergyCategory.ENVIRONMENT -> AllergyIntolerance.AllergyIntoleranceCategory.ENVIRONMENT
            AllergyCategory.BIOLOGIC -> AllergyIntolerance.AllergyIntoleranceCategory.BIOLOGIC
        }

    private fun toFhirCriticality(criticality: AllergyCriticality): AllergyIntolerance.AllergyIntoleranceCriticality =
        when (criticality) {
            AllergyCriticality.LOW -> AllergyIntolerance.AllergyIntoleranceCriticality.LOW
            AllergyCriticality.HIGH -> AllergyIntolerance.AllergyIntoleranceCriticality.HIGH
            AllergyCriticality.UNABLE_TO_ASSESS -> AllergyIntolerance.AllergyIntoleranceCriticality.UNABLETOASSESS
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
}
