package dev.ehr.fhir

import dev.ehr.encounter.Encounter
import dev.ehr.encounter.EncounterStatus
import dev.ehr.terminology.CodeableConcept
import org.hl7.fhir.r4.model.Coding
import org.hl7.fhir.r4.model.DateTimeType
import org.hl7.fhir.r4.model.Period
import org.hl7.fhir.r4.model.Reference
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.Date
import ca.uhn.fhir.model.api.TemporalPrecisionEnum
import org.hl7.fhir.r4.model.Encounter as FhirEncounter

@Component
class EncounterFhirMapper {
    fun toFhirEncounter(
        encounter: Encounter,
        classConcept: CodeableConcept,
    ): FhirEncounter {
        val fhirEncounter = FhirEncounter()

        fhirEncounter.id = encounter.id.value.toString()
        fhirEncounter.meta.versionId = encounter.version.toString()
        fhirEncounter.meta.lastUpdated = Date.from(encounter.updatedAt)
        // us-core-encounter requires Encounter.type (1..*), which this model
        // does not capture (class only) — base R4, recorded in the gap report.
        fhirEncounter.status = toFhirStatus(encounter.status)
        fhirEncounter.class_ = Coding()
            .setSystem(classConcept.primaryCoding.system)
            .setCode(classConcept.primaryCoding.code)
            .setDisplay(classConcept.primaryCoding.display)
        fhirEncounter.subject = Reference("Patient/${encounter.patientId.value}")

        val period = Period()
        period.startElement = utcDateTime(encounter.periodStart)
        encounter.periodEnd?.let { period.endElement = utcDateTime(it) }
        fhirEncounter.period = period

        return fhirEncounter
    }

    private fun toFhirStatus(status: EncounterStatus): FhirEncounter.EncounterStatus =
        when (status) {
            EncounterStatus.PLANNED -> FhirEncounter.EncounterStatus.PLANNED
            EncounterStatus.IN_PROGRESS -> FhirEncounter.EncounterStatus.INPROGRESS
            EncounterStatus.FINISHED -> FhirEncounter.EncounterStatus.FINISHED
            EncounterStatus.CANCELLED -> FhirEncounter.EncounterStatus.CANCELLED
            EncounterStatus.ENTERED_IN_ERROR -> FhirEncounter.EncounterStatus.ENTEREDINERROR
        }

    private fun utcDateTime(instant: Instant): DateTimeType =
        DateTimeType(Date.from(instant), TemporalPrecisionEnum.SECOND).apply { setTimeZoneZulu(true) }
}
