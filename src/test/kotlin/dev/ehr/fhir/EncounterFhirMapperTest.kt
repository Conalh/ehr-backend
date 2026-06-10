package dev.ehr.fhir

import dev.ehr.encounter.Encounter
import dev.ehr.encounter.EncounterId
import dev.ehr.encounter.EncounterStatus
import dev.ehr.identity.OrganizationId
import dev.ehr.identity.UserId
import dev.ehr.patient.PatientId
import dev.ehr.terminology.CodeableConcept
import dev.ehr.terminology.CodeableConceptId
import dev.ehr.terminology.Coding
import dev.ehr.terminology.CodingId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.Date
import java.util.UUID
import org.hl7.fhir.r4.model.Encounter as FhirEncounter

class EncounterFhirMapperTest {
    private val mapper = EncounterFhirMapper()

    @Test
    fun `maps identity status class subject period and metadata`() {
        val encounter = encounter(
            status = EncounterStatus.IN_PROGRESS,
            periodEnd = Instant.parse("2026-06-01T11:30:00Z"),
        )

        val fhirEncounter = mapper.toFhirEncounter(encounter, classConcept())

        assertEquals(encounter.id.value.toString(), fhirEncounter.idElement.idPart)
        assertEquals("4", fhirEncounter.meta.versionId)
        assertEquals(Date.from(encounter.updatedAt), fhirEncounter.meta.lastUpdated)
        assertEquals(FhirEncounter.EncounterStatus.INPROGRESS, fhirEncounter.status)
        assertEquals("http://terminology.hl7.org/CodeSystem/v3-ActCode", fhirEncounter.class_.system)
        assertEquals("AMB", fhirEncounter.class_.code)
        assertEquals("ambulatory", fhirEncounter.class_.display)
        assertEquals("Patient/${encounter.patientId.value}", fhirEncounter.subject.reference)
        assertEquals("2026-06-01T09:00:00Z", fhirEncounter.period.startElement.valueAsString)
        assertEquals("2026-06-01T11:30:00Z", fhirEncounter.period.endElement.valueAsString)
    }

    @Test
    fun `maps every internal status onto its fhir status`() {
        mapOf(
            EncounterStatus.PLANNED to FhirEncounter.EncounterStatus.PLANNED,
            EncounterStatus.IN_PROGRESS to FhirEncounter.EncounterStatus.INPROGRESS,
            EncounterStatus.FINISHED to FhirEncounter.EncounterStatus.FINISHED,
            EncounterStatus.CANCELLED to FhirEncounter.EncounterStatus.CANCELLED,
            EncounterStatus.ENTERED_IN_ERROR to FhirEncounter.EncounterStatus.ENTEREDINERROR,
        ).forEach { (internalStatus, fhirStatus) ->
            val fhirEncounter = mapper.toFhirEncounter(encounter(status = internalStatus), classConcept())
            assertEquals(fhirStatus, fhirEncounter.status, "expected $internalStatus to map to $fhirStatus")
        }
    }

    @Test
    fun `open encounter has no period end`() {
        val fhirEncounter = mapper.toFhirEncounter(encounter(periodEnd = null), classConcept())

        assertEquals("2026-06-01T09:00:00Z", fhirEncounter.period.startElement.valueAsString)
        assertNull(fhirEncounter.period.end)
    }

    private fun encounter(
        status: EncounterStatus = EncounterStatus.PLANNED,
        periodEnd: Instant? = null,
    ): Encounter =
        Encounter(
            id = EncounterId(UUID.randomUUID()),
            organizationId = OrganizationId(UUID.randomUUID()),
            patientId = PatientId(UUID.randomUUID()),
            status = status,
            classConceptId = CodeableConceptId(UUID.randomUUID()),
            periodStart = Instant.parse("2026-06-01T09:00:00Z"),
            periodEnd = periodEnd,
            version = 4,
            createdAt = Instant.parse("2026-06-01T08:00:00Z"),
            updatedAt = Instant.parse("2026-06-01T10:00:00Z"),
            createdBy = UserId(UUID.randomUUID()),
            updatedBy = null,
        )

    private fun classConcept(): CodeableConcept {
        val coding = Coding(
            id = CodingId(UUID.randomUUID()),
            codeSystemVersionId = null,
            system = "http://terminology.hl7.org/CodeSystem/v3-ActCode",
            version = null,
            code = "AMB",
            display = "ambulatory",
            userSelected = false,
            createdAt = Instant.parse("2026-06-01T08:00:00Z"),
        )
        return CodeableConcept(
            id = CodeableConceptId(UUID.randomUUID()),
            text = "ambulatory",
            bindingContext = null,
            primaryCoding = coding,
            codings = listOf(coding),
            createdAt = Instant.parse("2026-06-01T08:00:00Z"),
        )
    }
}
