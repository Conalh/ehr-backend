package dev.ehr.fhir

import dev.ehr.patient.IdentifierUse
import dev.ehr.patient.PatientAdministrativeGender
import dev.ehr.patient.PatientStatus
import dev.ehr.patient.PatientWithIdentifiers
import org.hl7.fhir.r4.model.DateTimeType
import org.hl7.fhir.r4.model.DateType
import org.hl7.fhir.r4.model.Enumerations
import org.hl7.fhir.r4.model.HumanName
import org.hl7.fhir.r4.model.Identifier
import org.hl7.fhir.r4.model.Patient
import org.hl7.fhir.r4.model.Period
import org.hl7.fhir.r4.model.Reference
import org.springframework.stereotype.Component
import java.util.Date

@Component
class PatientFhirMapper {
    fun toFhirPatient(source: PatientWithIdentifiers): Patient {
        val patient = source.patient
        val fhirPatient = Patient()

        fhirPatient.id = patient.id.value.toString()
        fhirPatient.meta.versionId = patient.version.toString()
        fhirPatient.meta.lastUpdated = Date.from(patient.updatedAt)
        fhirPatient.meta.addProfile(US_CORE_PATIENT_PROFILE)
        fhirPatient.active = patient.status == PatientStatus.ACTIVE
        fhirPatient.addName(
            HumanName()
                .setFamily(patient.familyName)
                .addGiven(patient.givenName),
        )
        patient.birthDate?.let { birthDate ->
            fhirPatient.birthDateElement = DateType(birthDate.toString())
        }
        // US Core requires gender 1..1: an unrecorded gender is honestly 'unknown'.
        fhirPatient.gender = toFhirGender(patient.administrativeGender ?: PatientAdministrativeGender.UNKNOWN)
        source.identifiers.forEach { identifier ->
            val fhirIdentifier = Identifier()
                .setSystem(identifier.system)
                .setValue(identifier.value)
            identifier.use?.let { use ->
                fhirIdentifier.use = toFhirIdentifierUse(use)
            }
            identifier.assignerText?.let { assignerText ->
                fhirIdentifier.assigner = Reference().setDisplay(assignerText)
            }
            if (identifier.periodStart != null || identifier.periodEnd != null) {
                val period = Period()
                identifier.periodStart?.let { period.startElement = DateTimeType(it.toString()) }
                identifier.periodEnd?.let { period.endElement = DateTimeType(it.toString()) }
                fhirIdentifier.period = period
            }
            fhirPatient.addIdentifier(fhirIdentifier)
        }
        // US Core requires identifier 1..*: the logical id is genuinely an
        // identifier in this system, so it backstops unregistered patients.
        if (fhirPatient.identifier.isEmpty()) {
            fhirPatient.addIdentifier(
                Identifier()
                    .setSystem(LOGICAL_ID_IDENTIFIER_SYSTEM)
                    .setValue(patient.id.value.toString()),
            )
        }

        return fhirPatient
    }

    companion object {
        const val US_CORE_PATIENT_PROFILE = "http://hl7.org/fhir/us/core/StructureDefinition/us-core-patient"
        const val LOGICAL_ID_IDENTIFIER_SYSTEM = "urn:ehr:patient-id"
    }

    private fun toFhirGender(gender: PatientAdministrativeGender): Enumerations.AdministrativeGender =
        when (gender) {
            PatientAdministrativeGender.MALE -> Enumerations.AdministrativeGender.MALE
            PatientAdministrativeGender.FEMALE -> Enumerations.AdministrativeGender.FEMALE
            PatientAdministrativeGender.OTHER -> Enumerations.AdministrativeGender.OTHER
            PatientAdministrativeGender.UNKNOWN -> Enumerations.AdministrativeGender.UNKNOWN
        }

    private fun toFhirIdentifierUse(use: IdentifierUse): Identifier.IdentifierUse =
        when (use) {
            IdentifierUse.USUAL -> Identifier.IdentifierUse.USUAL
            IdentifierUse.OFFICIAL -> Identifier.IdentifierUse.OFFICIAL
            IdentifierUse.TEMP -> Identifier.IdentifierUse.TEMP
            IdentifierUse.SECONDARY -> Identifier.IdentifierUse.SECONDARY
            IdentifierUse.OLD -> Identifier.IdentifierUse.OLD
        }
}
