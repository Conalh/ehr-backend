package dev.ehr.fhir

import dev.ehr.allergy.Allergy
import dev.ehr.allergy.AllergyCategory
import dev.ehr.allergy.AllergyClinicalStatus
import dev.ehr.allergy.AllergyCriticality
import dev.ehr.allergy.AllergyId
import dev.ehr.allergy.AllergyVerificationStatus
import dev.ehr.encounter.EncounterId
import dev.ehr.identity.OrganizationId
import dev.ehr.identity.UserId
import dev.ehr.patient.PatientId
import dev.ehr.terminology.CodeableConcept
import dev.ehr.terminology.CodeableConceptId
import dev.ehr.terminology.Coding
import dev.ehr.terminology.CodingId
import org.hl7.fhir.r4.model.AllergyIntolerance
import org.hl7.fhir.r4.model.DateTimeType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.util.Date
import java.util.UUID

class AllergyFhirMapperTest {
    private val mapper = AllergyFhirMapper()

    @Test
    fun `maps statuses code patient encounter category criticality onset and metadata`() {
        val allergy = allergy(
            encounterId = EncounterId(UUID.randomUUID()),
            category = AllergyCategory.FOOD,
            criticality = AllergyCriticality.HIGH,
            onsetDate = LocalDate.of(2020, 7, 4),
        )

        val fhirAllergy = mapper.toFhirAllergyIntolerance(allergy, codeConcept())

        assertEquals(allergy.id.value.toString(), fhirAllergy.idElement.idPart)
        assertEquals("1", fhirAllergy.meta.versionId)
        assertEquals(Date.from(allergy.updatedAt), fhirAllergy.meta.lastUpdated)
        assertEquals(
            "http://terminology.hl7.org/CodeSystem/allergyintolerance-clinical",
            fhirAllergy.clinicalStatus.codingFirstRep.system,
        )
        assertEquals("active", fhirAllergy.clinicalStatus.codingFirstRep.code)
        assertEquals(
            "http://terminology.hl7.org/CodeSystem/allergyintolerance-verification",
            fhirAllergy.verificationStatus.codingFirstRep.system,
        )
        assertEquals("confirmed", fhirAllergy.verificationStatus.codingFirstRep.code)
        assertEquals("http://snomed.info/sct", fhirAllergy.code.codingFirstRep.system)
        assertEquals("91935009", fhirAllergy.code.codingFirstRep.code)
        assertEquals("Allergy to peanut", fhirAllergy.code.text)
        assertEquals("Patient/${allergy.patientId.value}", fhirAllergy.patient.reference)
        assertEquals("Encounter/${allergy.encounterId!!.value}", fhirAllergy.encounter.reference)
        assertEquals(AllergyIntolerance.AllergyIntoleranceCategory.FOOD, fhirAllergy.category[0].value)
        assertEquals(AllergyIntolerance.AllergyIntoleranceCriticality.HIGH, fhirAllergy.criticality)
        assertEquals("2020-07-04", (fhirAllergy.onset as DateTimeType).valueAsString)
        assertEquals(Date.from(allergy.recordedAt), fhirAllergy.recordedDate)
    }

    @Test
    fun `maps every category and criticality value`() {
        AllergyCategory.entries.forEach { category ->
            val fhirAllergy = mapper.toFhirAllergyIntolerance(allergy(category = category), codeConcept())
            assertEquals(category.dbValue, fhirAllergy.category[0].value.toCode())
        }
        AllergyCriticality.entries.forEach { criticality ->
            val fhirAllergy = mapper.toFhirAllergyIntolerance(allergy(criticality = criticality), codeConcept())
            assertEquals(criticality.dbValue, fhirAllergy.criticality.toCode())
        }
    }

    @Test
    fun `minimal allergy omits encounter category criticality and onset`() {
        val fhirAllergy = mapper.toFhirAllergyIntolerance(allergy(), codeConcept())

        assertTrue(fhirAllergy.encounter.isEmpty)
        assertTrue(fhirAllergy.category.isEmpty())
        assertNull(fhirAllergy.criticality)
        assertNull(fhirAllergy.onset)
    }

    private fun allergy(
        encounterId: EncounterId? = null,
        category: AllergyCategory? = null,
        criticality: AllergyCriticality? = null,
        onsetDate: LocalDate? = null,
    ): Allergy =
        Allergy(
            id = AllergyId(UUID.randomUUID()),
            organizationId = OrganizationId(UUID.randomUUID()),
            patientId = PatientId(UUID.randomUUID()),
            encounterId = encounterId,
            clinicalStatus = AllergyClinicalStatus.ACTIVE,
            verificationStatus = AllergyVerificationStatus.CONFIRMED,
            codeConceptId = CodeableConceptId(UUID.randomUUID()),
            category = category,
            criticality = criticality,
            onsetDate = onsetDate,
            recordedAt = Instant.parse("2026-06-02T10:15:00Z"),
            version = 1,
            createdAt = Instant.parse("2026-06-02T10:15:00Z"),
            updatedAt = Instant.parse("2026-06-03T08:00:00Z"),
            createdBy = UserId(UUID.randomUUID()),
            updatedBy = null,
        )

    private fun codeConcept(): CodeableConcept {
        val coding = Coding(
            id = CodingId(UUID.randomUUID()),
            codeSystemVersionId = null,
            system = "http://snomed.info/sct",
            version = null,
            code = "91935009",
            display = "Allergy to peanut",
            userSelected = false,
            createdAt = Instant.parse("2026-06-01T08:00:00Z"),
        )
        return CodeableConcept(
            id = CodeableConceptId(UUID.randomUUID()),
            text = "Allergy to peanut",
            bindingContext = null,
            primaryCoding = coding,
            codings = listOf(coding),
            createdAt = Instant.parse("2026-06-01T08:00:00Z"),
        )
    }
}
