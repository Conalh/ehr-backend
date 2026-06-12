package dev.ehr.fhir

import dev.ehr.identity.OrganizationId
import dev.ehr.identity.UserId
import dev.ehr.patient.IdentifierUse
import dev.ehr.patient.Patient
import dev.ehr.patient.PatientAdministrativeGender
import dev.ehr.patient.PatientId
import dev.ehr.patient.PatientIdentifier
import dev.ehr.patient.PatientIdentifierId
import dev.ehr.patient.PatientStatus
import dev.ehr.patient.PatientWithIdentifiers
import org.hl7.fhir.r4.model.Enumerations
import org.hl7.fhir.r4.model.Identifier
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.util.Date
import java.util.UUID

class PatientFhirMapperTest {
    private val mapper = PatientFhirMapper()

    @Test
    fun `maps demographics version and metadata`() {
        val patient = patient(
            status = PatientStatus.ACTIVE,
            administrativeGender = PatientAdministrativeGender.FEMALE,
            birthDate = LocalDate.of(1990, 4, 2),
        )

        val fhirPatient = mapper.toFhirPatient(PatientWithIdentifiers(patient, emptyList()))

        assertEquals(patient.id.value.toString(), fhirPatient.idElement.idPart)
        assertEquals(patient.version.toString(), fhirPatient.meta.versionId)
        assertEquals(Date.from(patient.updatedAt), fhirPatient.meta.lastUpdated)
        assertTrue(fhirPatient.active)
        assertEquals("Patient", fhirPatient.nameFirstRep.family)
        assertEquals("Synthetic", fhirPatient.nameFirstRep.givenAsSingleString)
        assertEquals("1990-04-02", fhirPatient.birthDateElement.valueAsString)
        assertEquals(Enumerations.AdministrativeGender.FEMALE, fhirPatient.gender)
    }

    @Test
    fun `maps non active statuses to inactive`() {
        listOf(
            PatientStatus.INACTIVE,
            PatientStatus.ENTERED_IN_ERROR,
        ).forEach { status ->
            val fhirPatient = mapper.toFhirPatient(
                PatientWithIdentifiers(patient(status = status), emptyList()),
            )
            assertFalse(fhirPatient.active, "expected $status to map to active=false")
        }
    }

    @Test
    fun `maps all administrative genders and defaults absent to unknown`() {
        mapOf(
            PatientAdministrativeGender.MALE to Enumerations.AdministrativeGender.MALE,
            PatientAdministrativeGender.FEMALE to Enumerations.AdministrativeGender.FEMALE,
            PatientAdministrativeGender.OTHER to Enumerations.AdministrativeGender.OTHER,
            PatientAdministrativeGender.UNKNOWN to Enumerations.AdministrativeGender.UNKNOWN,
        ).forEach { (internalGender, fhirGender) ->
            val fhirPatient = mapper.toFhirPatient(
                PatientWithIdentifiers(patient(administrativeGender = internalGender), emptyList()),
            )
            assertEquals(fhirGender, fhirPatient.gender)
        }

        // US Core requires gender 1..1: an unrecorded gender maps to 'unknown'.
        val withoutGender = mapper.toFhirPatient(
            PatientWithIdentifiers(patient(administrativeGender = null), emptyList()),
        )
        assertEquals(Enumerations.AdministrativeGender.UNKNOWN, withoutGender.gender)
    }

    @Test
    fun `maps identifiers with use period and assigner`() {
        val patient = patient()
        val identifier = PatientIdentifier(
            id = PatientIdentifierId(UUID.randomUUID()),
            organizationId = patient.organizationId,
            patientId = patient.id,
            system = "urn:ehr:mrn",
            value = "MRN-42",
            use = IdentifierUse.OFFICIAL,
            typeConceptId = null,
            assignerText = "North Clinic",
            periodStart = LocalDate.of(2020, 1, 1),
            periodEnd = LocalDate.of(2025, 12, 31),
            createdAt = Instant.now(),
        )

        val fhirPatient = mapper.toFhirPatient(PatientWithIdentifiers(patient, listOf(identifier)))

        assertEquals(1, fhirPatient.identifier.size)
        val fhirIdentifier = fhirPatient.identifierFirstRep
        assertEquals("urn:ehr:mrn", fhirIdentifier.system)
        assertEquals("MRN-42", fhirIdentifier.value)
        assertEquals(Identifier.IdentifierUse.OFFICIAL, fhirIdentifier.use)
        assertEquals("North Clinic", fhirIdentifier.assigner.display)
        assertEquals("2020-01-01", fhirIdentifier.period.startElement.valueAsString)
        assertEquals("2025-12-31", fhirIdentifier.period.endElement.valueAsString)
    }

    @Test
    fun `maps bare identifier without optional fields`() {
        val patient = patient()
        val identifier = PatientIdentifier(
            id = PatientIdentifierId(UUID.randomUUID()),
            organizationId = patient.organizationId,
            patientId = patient.id,
            system = "urn:ehr:mrn",
            value = "MRN-1",
            use = null,
            typeConceptId = null,
            assignerText = null,
            periodStart = null,
            periodEnd = null,
            createdAt = Instant.now(),
        )

        val fhirIdentifier = mapper.toFhirPatient(
            PatientWithIdentifiers(patient, listOf(identifier)),
        ).identifierFirstRep

        assertEquals("urn:ehr:mrn", fhirIdentifier.system)
        assertEquals("MRN-1", fhirIdentifier.value)
        assertNull(fhirIdentifier.use)
        assertTrue(fhirIdentifier.assigner.isEmpty)
        assertTrue(fhirIdentifier.period.isEmpty)
    }

    private fun patient(
        status: PatientStatus = PatientStatus.ACTIVE,
        administrativeGender: PatientAdministrativeGender? = null,
        birthDate: LocalDate? = null,
    ): Patient =
        Patient(
            id = PatientId(UUID.randomUUID()),
            organizationId = OrganizationId(UUID.randomUUID()),
            status = status,
            givenName = "Synthetic",
            familyName = "Patient",
            birthDate = birthDate,
            administrativeGender = administrativeGender,
            version = 3,
            createdAt = Instant.parse("2026-06-01T10:00:00Z"),
            updatedAt = Instant.parse("2026-06-02T11:30:00Z"),
            createdBy = UserId(UUID.randomUUID()),
            updatedBy = null,
        )
}
