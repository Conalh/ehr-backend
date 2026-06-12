package dev.ehr.fhir

import ca.uhn.fhir.context.FhirContext
import ca.uhn.fhir.context.support.DefaultProfileValidationSupport
import ca.uhn.fhir.validation.FhirValidator
import ca.uhn.fhir.validation.ResultSeverityEnum
import dev.ehr.allergy.Allergy
import dev.ehr.allergy.AllergyCategory
import dev.ehr.allergy.AllergyClinicalStatus
import dev.ehr.allergy.AllergyCriticality
import dev.ehr.allergy.AllergyId
import dev.ehr.allergy.AllergyVerificationStatus
import dev.ehr.condition.Condition
import dev.ehr.condition.ConditionClinicalStatus
import dev.ehr.condition.ConditionId
import dev.ehr.condition.ConditionVerificationStatus
import dev.ehr.diagnostics.DiagnosticReport
import dev.ehr.diagnostics.DiagnosticReportId
import dev.ehr.diagnostics.DiagnosticReportStatus
import dev.ehr.encounter.Encounter
import dev.ehr.encounter.EncounterId
import dev.ehr.encounter.EncounterStatus
import dev.ehr.identity.OrganizationId
import dev.ehr.identity.UserId
import dev.ehr.medication.MedicationStatement
import dev.ehr.medication.MedicationStatementId
import dev.ehr.medication.MedicationStatementStatus
import dev.ehr.note.ClinicalNote
import dev.ehr.note.ClinicalNoteId
import dev.ehr.note.ClinicalNoteStatus
import dev.ehr.observation.Observation
import dev.ehr.observation.ObservationCategory
import dev.ehr.observation.ObservationId
import dev.ehr.observation.ObservationStatus
import dev.ehr.observation.ObservationValue
import dev.ehr.patient.IdentifierUse
import dev.ehr.patient.Patient
import dev.ehr.patient.PatientAdministrativeGender
import dev.ehr.patient.PatientId
import dev.ehr.patient.PatientIdentifier
import dev.ehr.patient.PatientIdentifierId
import dev.ehr.patient.PatientStatus
import dev.ehr.patient.PatientWithIdentifiers
import dev.ehr.provenance.ProvenanceActivity
import dev.ehr.provenance.ProvenanceEvent
import dev.ehr.provenance.ProvenanceSourceType
import dev.ehr.terminology.CodeableConcept
import dev.ehr.terminology.CodeableConceptId
import dev.ehr.terminology.Coding
import dev.ehr.terminology.CodingId
import org.hl7.fhir.common.hapi.validation.support.InMemoryTerminologyServerValidationSupport
import org.hl7.fhir.common.hapi.validation.support.ValidationSupportChain
import org.hl7.fhir.common.hapi.validation.validator.FhirInstanceValidator
import org.hl7.fhir.instance.model.api.IBaseResource
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Conformance smoke tests: every resource produced by the FHIR mappers must
 * validate against base FHIR R4 with no error-severity issues.
 */
class FhirConformanceValidationTest {
    @Test
    fun `mapper outputs validate against base r4`() {
        val organizationId = OrganizationId(UUID.randomUUID())
        val patientId = PatientId(UUID.randomUUID())
        val encounterId = EncounterId(UUID.randomUUID())
        val now = Instant.parse("2026-06-01T09:00:00Z")
        val snomed = concept("http://snomed.info/sct", "38341003", "Hypertensive disorder")
        val loinc = concept("http://loinc.org", "8867-4", "Heart rate")
        val actCode = concept("http://terminology.hl7.org/CodeSystem/v3-ActCode", "AMB", "ambulatory")

        val patient = Patient(
            id = patientId,
            organizationId = organizationId,
            status = PatientStatus.ACTIVE,
            givenName = "Synthetic",
            familyName = "Patient",
            birthDate = LocalDate.of(1990, 4, 2),
            administrativeGender = PatientAdministrativeGender.FEMALE,
            version = 1,
            createdAt = now,
            updatedAt = now,
            createdBy = UserId(UUID.randomUUID()),
            updatedBy = null,
        )
        val identifier = PatientIdentifier(
            id = PatientIdentifierId(UUID.randomUUID()),
            organizationId = organizationId,
            patientId = patientId,
            system = "urn:ehr:mrn",
            value = "MRN-1",
            use = IdentifierUse.OFFICIAL,
            typeConceptId = null,
            assignerText = "North Clinic",
            periodStart = LocalDate.of(2020, 1, 1),
            periodEnd = null,
            createdAt = now,
        )
        val encounter = Encounter(
            id = encounterId,
            organizationId = organizationId,
            patientId = patientId,
            status = EncounterStatus.FINISHED,
            classConceptId = actCode.id,
            periodStart = now,
            periodEnd = now.plusSeconds(3600),
            version = 2,
            createdAt = now,
            updatedAt = now,
            createdBy = null,
            updatedBy = null,
        )
        val condition = Condition(
            id = ConditionId(UUID.randomUUID()),
            organizationId = organizationId,
            patientId = patientId,
            encounterId = encounterId,
            clinicalStatus = ConditionClinicalStatus.ACTIVE,
            verificationStatus = ConditionVerificationStatus.CONFIRMED,
            codeConceptId = snomed.id,
            onsetDate = LocalDate.of(2026, 1, 15),
            abatementDate = null,
            recordedAt = now,
            version = 1,
            createdAt = now,
            updatedAt = now,
            createdBy = null,
            updatedBy = null,
        )
        val allergy = Allergy(
            id = AllergyId(UUID.randomUUID()),
            organizationId = organizationId,
            patientId = patientId,
            encounterId = null,
            clinicalStatus = AllergyClinicalStatus.ACTIVE,
            verificationStatus = AllergyVerificationStatus.CONFIRMED,
            codeConceptId = snomed.id,
            category = AllergyCategory.FOOD,
            criticality = AllergyCriticality.HIGH,
            onsetDate = LocalDate.of(2020, 7, 4),
            recordedAt = now,
            version = 1,
            createdAt = now,
            updatedAt = now,
            createdBy = null,
            updatedBy = null,
        )
        val observation = Observation(
            id = ObservationId(UUID.randomUUID()),
            organizationId = organizationId,
            patientId = patientId,
            encounterId = encounterId,
            status = ObservationStatus.FINAL,
            category = ObservationCategory.VITAL_SIGNS,
            codeConceptId = loinc.id,
            value = ObservationValue.Quantity(BigDecimal("72"), "/min"),
            effectiveAt = now,
            version = 1,
            createdAt = now,
            updatedAt = now,
            createdBy = null,
            updatedBy = null,
        )
        val medication = MedicationStatement(
            id = MedicationStatementId(UUID.randomUUID()),
            organizationId = organizationId,
            patientId = patientId,
            encounterId = null,
            status = MedicationStatementStatus.ACTIVE,
            medicationConceptId = snomed.id,
            dosageText = "10 mg orally once daily",
            effectiveStart = LocalDate.of(2026, 1, 1),
            effectiveEnd = null,
            recordedAt = now,
            version = 1,
            createdAt = now,
            updatedAt = now,
            createdBy = null,
            updatedBy = null,
        )
        val note = ClinicalNote(
            id = ClinicalNoteId(UUID.randomUUID()),
            organizationId = organizationId,
            patientId = patientId,
            encounterId = encounterId,
            status = ClinicalNoteStatus.CURRENT,
            typeConceptId = loinc.id,
            title = "Progress note",
            contentText = "Synthetic note body.",
            authoredAt = now,
            version = 1,
            createdAt = now,
            updatedAt = now,
            createdBy = null,
            updatedBy = null,
        )
        val report = DiagnosticReport(
            id = DiagnosticReportId(UUID.randomUUID()),
            organizationId = organizationId,
            patientId = patientId,
            encounterId = encounterId,
            orderId = dev.ehr.order.OrderId(UUID.randomUUID()),
            status = DiagnosticReportStatus.FINAL,
            codeConceptId = loinc.id,
            conclusionText = "Normal.",
            issuedAt = now,
            resultObservationIds = listOf(observation.id),
            version = 1,
            createdAt = now,
            updatedAt = now,
            createdBy = null,
            updatedBy = null,
        )
        val provenanceEvent = ProvenanceEvent(
            id = UUID.randomUUID(),
            organizationId = organizationId,
            patientId = patientId.value,
            targetResourceType = "CONDITION",
            targetResourceId = condition.id.value,
            targetVersion = 1,
            activity = ProvenanceActivity.CREATED,
            agentUserId = UserId(UUID.randomUUID()),
            agentClientId = null,
            recordedAt = now,
            sourceType = ProvenanceSourceType.CLINICIAN_AUTHORED,
            sourceReference = null,
            priorResourceVersion = null,
            syntheticGenerationRunId = null,
        )

        val careTeamUser = dev.ehr.identity.User(
            id = UserId(UUID.randomUUID()),
            externalSubject = "conformance-user",
            email = "conformance-user@example.test",
            displayName = "Conformance Clinician",
            status = dev.ehr.identity.UserStatus.ACTIVE,
            createdAt = now,
            updatedAt = now,
        )
        val careTeamMembership = dev.ehr.careteam.CareTeamMembership(
            id = dev.ehr.careteam.CareTeamMembershipId(UUID.randomUUID()),
            organizationId = organizationId,
            patientId = patientId,
            userId = careTeamUser.id,
            role = dev.ehr.careteam.CareTeamRole.ATTENDING,
            origin = dev.ehr.careteam.CareTeamMembershipOrigin.EXPLICIT,
            periodStart = now,
            periodEnd = null,
            createdAt = now,
            createdBy = careTeamUser.id,
        )

        val resources: Map<String, IBaseResource> = mapOf(
            "Patient" to PatientFhirMapper().toFhirPatient(PatientWithIdentifiers(patient, listOf(identifier))),
            "Practitioner" to PractitionerFhirMapper().toFhirPractitioner(
                dev.ehr.identity.Practitioner(
                    id = dev.ehr.identity.PractitionerId(UUID.randomUUID()),
                    userId = careTeamUser.id,
                    npi = "1234567893",
                    displayName = "Conformance Clinician",
                    status = dev.ehr.identity.PractitionerStatus.ACTIVE,
                    createdAt = now,
                    updatedAt = now,
                ),
            ),
            "CareTeam" to CareTeamFhirMapper().toFhirCareTeam(
                patientId,
                listOf(careTeamMembership),
                mapOf(careTeamUser.id to careTeamUser),
            ),
            "Encounter" to EncounterFhirMapper().toFhirEncounter(encounter, actCode),
            "Condition" to ConditionFhirMapper().toFhirCondition(condition, snomed),
            "AllergyIntolerance" to AllergyFhirMapper().toFhirAllergyIntolerance(allergy, snomed),
            "Observation" to ObservationFhirMapper().toFhirObservation(observation, loinc, null),
            "MedicationStatement" to MedicationStatementFhirMapper().toFhirMedicationStatement(medication, snomed),
            "DocumentReference" to DocumentReferenceFhirMapper().toFhirDocumentReference(note, loinc),
            "DiagnosticReport" to DiagnosticReportFhirMapper().toFhirDiagnosticReport(report, loinc),
            "Provenance" to ProvenanceFhirMapper().toFhirProvenance(provenanceEvent),
            "OperationOutcome" to org.hl7.fhir.r4.model.OperationOutcome().addIssue(
                org.hl7.fhir.r4.model.OperationOutcome.OperationOutcomeIssueComponent()
                    .setSeverity(org.hl7.fhir.r4.model.OperationOutcome.IssueSeverity.ERROR)
                    .setCode(org.hl7.fhir.r4.model.OperationOutcome.IssueType.NOTFOUND)
                    .setDiagnostics("Not found"),
            ),
        )

        // The Patient example must actually claim the US Core profile —
        // otherwise the loop below would only prove base R4.
        val patientProfiles = (resources["Patient"] as org.hl7.fhir.r4.model.Patient).meta.profile.map { it.value }
        assertTrue(
            "http://hl7.org/fhir/us/core/StructureDefinition/us-core-patient" in patientProfiles,
            "Patient must declare the US Core profile it is validated against",
        )

        resources.forEach { (name, resource) ->
            val result = validator.validateWithResult(resource)
            val errors = result.messages.filter {
                it.severity == ResultSeverityEnum.ERROR || it.severity == ResultSeverityEnum.FATAL
            }
            assertTrue(
                errors.isEmpty(),
                "$name failed validation: ${errors.joinToString { "${it.locationString}: ${it.message}" }}",
            )
        }
    }

    private fun concept(
        system: String,
        code: String,
        display: String,
    ): CodeableConcept {
        val coding = Coding(
            id = CodingId(UUID.randomUUID()),
            codeSystemVersionId = null,
            system = system,
            version = null,
            code = code,
            display = display,
            userSelected = false,
            createdAt = Instant.parse("2026-06-01T08:00:00Z"),
        )
        return CodeableConcept(
            id = CodeableConceptId(UUID.randomUUID()),
            text = display,
            bindingContext = null,
            primaryCoding = coding,
            codings = listOf(coding),
            createdAt = Instant.parse("2026-06-01T08:00:00Z"),
        )
    }

    companion object {
        private lateinit var validator: FhirValidator

        @JvmStatic
        @BeforeAll
        fun setUpValidator() {
            val context = FhirContext.forR4()
            // US Core structure definitions: resources that stamp a US Core
            // meta.profile are validated against it, not just base R4.
            val usCore = org.hl7.fhir.common.hapi.validation.support.NpmPackageValidationSupport(context)
            usCore.loadPackageFromClasspath("classpath:fhir-packages/hl7.fhir.us.core-6.1.0.tgz")
            val supportChain = ValidationSupportChain(
                usCore,
                DefaultProfileValidationSupport(context),
                // Supplies BCP-13 mime types, languages, and UCUM that the default profiles reference.
                org.hl7.fhir.common.hapi.validation.support.CommonCodeSystemsTerminologyService(context),
                InMemoryTerminologyServerValidationSupport(context),
                org.hl7.fhir.common.hapi.validation.support.SnapshotGeneratingValidationSupport(context),
            )
            validator = context.newValidator().registerValidatorModule(FhirInstanceValidator(supportChain))
        }
    }
}
