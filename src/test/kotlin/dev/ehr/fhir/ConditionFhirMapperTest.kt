package dev.ehr.fhir

import dev.ehr.condition.Condition
import dev.ehr.condition.ConditionClinicalStatus
import dev.ehr.condition.ConditionId
import dev.ehr.condition.ConditionVerificationStatus
import dev.ehr.encounter.EncounterId
import dev.ehr.identity.OrganizationId
import dev.ehr.identity.UserId
import dev.ehr.patient.PatientId
import dev.ehr.terminology.CodeableConcept
import dev.ehr.terminology.CodeableConceptId
import dev.ehr.terminology.Coding
import dev.ehr.terminology.CodingId
import org.hl7.fhir.r4.model.DateTimeType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.util.Date
import java.util.UUID

class ConditionFhirMapperTest {
    private val mapper = ConditionFhirMapper()

    @Test
    fun `maps statuses code subject encounter dates and metadata`() {
        val condition = condition(
            encounterId = EncounterId(UUID.randomUUID()),
            onsetDate = LocalDate.of(2026, 1, 15),
            abatementDate = LocalDate.of(2026, 3, 20),
        )

        val fhirCondition = mapper.toFhirCondition(condition, codeConcept())

        assertEquals(condition.id.value.toString(), fhirCondition.idElement.idPart)
        assertEquals("2", fhirCondition.meta.versionId)
        assertEquals(Date.from(condition.updatedAt), fhirCondition.meta.lastUpdated)
        assertEquals(
            "http://terminology.hl7.org/CodeSystem/condition-clinical",
            fhirCondition.clinicalStatus.codingFirstRep.system,
        )
        assertEquals("active", fhirCondition.clinicalStatus.codingFirstRep.code)
        assertEquals(
            "http://terminology.hl7.org/CodeSystem/condition-ver-status",
            fhirCondition.verificationStatus.codingFirstRep.system,
        )
        assertEquals("confirmed", fhirCondition.verificationStatus.codingFirstRep.code)
        assertEquals("http://snomed.info/sct", fhirCondition.code.codingFirstRep.system)
        assertEquals("38341003", fhirCondition.code.codingFirstRep.code)
        assertEquals("Hypertensive disorder", fhirCondition.code.codingFirstRep.display)
        assertEquals("Hypertensive disorder", fhirCondition.code.text)
        assertEquals("Patient/${condition.patientId.value}", fhirCondition.subject.reference)
        assertEquals("Encounter/${condition.encounterId!!.value}", fhirCondition.encounter.reference)
        assertEquals("2026-01-15", (fhirCondition.onset as DateTimeType).valueAsString)
        assertEquals("2026-03-20", (fhirCondition.abatement as DateTimeType).valueAsString)
        assertEquals(Date.from(condition.recordedAt), fhirCondition.recordedDate)
    }

    @Test
    fun `maps every clinical and verification status code`() {
        ConditionClinicalStatus.entries.forEach { status ->
            val fhirCondition = mapper.toFhirCondition(condition(clinicalStatus = status), codeConcept())
            assertEquals(status.dbValue, fhirCondition.clinicalStatus.codingFirstRep.code)
        }
        ConditionVerificationStatus.entries.forEach { status ->
            val fhirCondition = mapper.toFhirCondition(condition(verificationStatus = status), codeConcept())
            assertEquals(status.dbValue, fhirCondition.verificationStatus.codingFirstRep.code)
        }
    }

    @Test
    fun `minimal condition omits encounter and dates`() {
        val fhirCondition = mapper.toFhirCondition(condition(), codeConcept())

        assertTrue(fhirCondition.encounter.isEmpty)
        assertNull(fhirCondition.onset)
        assertNull(fhirCondition.abatement)
    }

    private fun condition(
        clinicalStatus: ConditionClinicalStatus = ConditionClinicalStatus.ACTIVE,
        verificationStatus: ConditionVerificationStatus = ConditionVerificationStatus.CONFIRMED,
        encounterId: EncounterId? = null,
        onsetDate: LocalDate? = null,
        abatementDate: LocalDate? = null,
    ): Condition =
        Condition(
            id = ConditionId(UUID.randomUUID()),
            organizationId = OrganizationId(UUID.randomUUID()),
            patientId = PatientId(UUID.randomUUID()),
            encounterId = encounterId,
            clinicalStatus = clinicalStatus,
            verificationStatus = verificationStatus,
            codeConceptId = CodeableConceptId(UUID.randomUUID()),
            onsetDate = onsetDate,
            abatementDate = abatementDate,
            recordedAt = Instant.parse("2026-06-02T10:15:00Z"),
            version = 2,
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
            code = "38341003",
            display = "Hypertensive disorder",
            userSelected = false,
            createdAt = Instant.parse("2026-06-01T08:00:00Z"),
        )
        return CodeableConcept(
            id = CodeableConceptId(UUID.randomUUID()),
            text = "Hypertensive disorder",
            bindingContext = null,
            primaryCoding = coding,
            codings = listOf(coding),
            createdAt = Instant.parse("2026-06-01T08:00:00Z"),
        )
    }
}
