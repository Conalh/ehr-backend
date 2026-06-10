package dev.ehr.fhir

import dev.ehr.encounter.Encounter
import dev.ehr.encounter.EncounterCreateCommand
import dev.ehr.encounter.EncounterRepository
import dev.ehr.identity.MembershipRepository
import dev.ehr.identity.MembershipRole
import dev.ehr.identity.Organization
import dev.ehr.identity.OrganizationRepository
import dev.ehr.identity.UserRepository
import dev.ehr.note.ClinicalNote
import dev.ehr.note.ClinicalNoteCreateCommand
import dev.ehr.note.ClinicalNoteRepository
import dev.ehr.patient.Patient
import dev.ehr.patient.PatientCreateCommand
import dev.ehr.patient.PatientRepository
import dev.ehr.terminology.CanonicalCodeSystems
import dev.ehr.terminology.CodeableConcept
import dev.ehr.terminology.CodeableConceptRepository
import dev.ehr.terminology.CodingRepository
import dev.ehr.testsupport.DevJwtFactory
import dev.ehr.testsupport.DevJwtTestConfiguration
import dev.ehr.testsupport.EncounterTestFixtures
import dev.ehr.testsupport.PostgresIntegrationTest
import dev.ehr.testsupport.TerminologyTestFixtures
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import java.time.Instant
import java.util.Base64
import java.util.UUID

@AutoConfigureMockMvc
@Import(DevJwtTestConfiguration::class)
class DocumentReferenceFhirApiIntegrationTest : PostgresIntegrationTest() {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var organizationRepository: OrganizationRepository

    @Autowired
    lateinit var userRepository: UserRepository

    @Autowired
    lateinit var membershipRepository: MembershipRepository

    @Autowired
    lateinit var patientRepository: PatientRepository

    @Autowired
    lateinit var encounterRepository: EncounterRepository

    @Autowired
    lateinit var clinicalNoteRepository: ClinicalNoteRepository

    @Autowired
    lateinit var codingRepository: CodingRepository

    @Autowired
    lateinit var codeableConceptRepository: CodeableConceptRepository

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    lateinit var jwtEncoder: JwtEncoder

    lateinit var noteTypeConcept: CodeableConcept
    lateinit var classConcept: CodeableConcept

    @BeforeEach
    fun setUpConcepts() {
        noteTypeConcept = TerminologyTestFixtures(codingRepository, codeableConceptRepository)
            .findOrCreateConcept(
                system = CanonicalCodeSystems.LOINC,
                code = "11506-3",
                display = "Progress note",
            )
        classConcept = EncounterTestFixtures(codingRepository, codeableConceptRepository)
            .createEncounterClassConcept()
    }

    @Test
    fun `fhir document reference endpoints reject unauthenticated requests without audit`() {
        val correlationId = "fhir-doc-unauth-${UUID.randomUUID()}"

        mockMvc.get("/fhir/r4/DocumentReference/${UUID.randomUUID()}") {
            header("X-Correlation-Id", correlationId)
        }.andExpect {
            status { isUnauthorized() }
        }

        assertEquals(0, auditCount(correlationId))
    }

    @Test
    fun `clinician can read a fhir document reference with inline note content`() {
        val correlationId = "fhir-doc-read-${UUID.randomUUID()}"
        val member = createMember(MembershipRole.CLINICIAN, "user/DocumentReference.read")
        val patient = createPatient(member.organization)
        val encounter = createEncounter(member.organization, patient)
        val note = createNote(member.organization, patient, encounter)
        val expectedData = Base64.getEncoder().encodeToString(note.contentText.toByteArray())

        mockMvc.get("/fhir/r4/DocumentReference/${note.id.value}") {
            header("Authorization", "Bearer ${member.token}")
            header("X-Correlation-Id", correlationId)
        }.andExpect {
            status { isOk() }
            content { contentTypeCompatibleWith("application/fhir+json") }
            jsonPath("$.resourceType") { value("DocumentReference") }
            jsonPath("$.id") { value(note.id.value.toString()) }
            jsonPath("$.status") { value("current") }
            jsonPath("$.type.coding[0].system") { value("http://loinc.org") }
            jsonPath("$.type.coding[0].code") { value("11506-3") }
            jsonPath("$.subject.reference") { value("Patient/${patient.id.value}") }
            jsonPath("$.context.encounter[0].reference") { value("Encounter/${encounter.id.value}") }
            jsonPath("$.description") { value("Progress note") }
            jsonPath("$.content[0].attachment.contentType") { value("text/plain") }
            jsonPath("$.content[0].attachment.data") { value(expectedData) }
        }

        val audit = auditRow(correlationId)
        assertEquals("NOTE", audit.resourceType)
        assertEquals("READ", audit.operation)
        assertEquals("SUCCESS", audit.outcome)
        assertEquals(patient.id.value.toString(), audit.patientId)
    }

    @Test
    fun `cross organization fhir document reference read returns operation outcome not found`() {
        val member = createMember(MembershipRole.CLINICIAN, "user/DocumentReference.read")
        val otherOrganization = createOrganization()
        val otherPatient = createPatient(otherOrganization)
        val otherEncounter = createEncounter(otherOrganization, otherPatient)
        val otherNote = createNote(otherOrganization, otherPatient, otherEncounter)

        mockMvc.get("/fhir/r4/DocumentReference/${otherNote.id.value}") {
            header("Authorization", "Bearer ${member.token}")
        }.andExpect {
            status { isNotFound() }
            jsonPath("$.resourceType") { value("OperationOutcome") }
            jsonPath("$.issue[0].code") { value("not-found") }
        }
    }

    @Test
    fun `staff fhir document reference read returns operation outcome forbidden`() {
        val member = createMember(MembershipRole.STAFF, "user/*.read")
        val patient = createPatient(member.organization)
        val encounter = createEncounter(member.organization, patient)
        val note = createNote(member.organization, patient, encounter)

        mockMvc.get("/fhir/r4/DocumentReference/${note.id.value}") {
            header("Authorization", "Bearer ${member.token}")
        }.andExpect {
            status { isForbidden() }
            jsonPath("$.resourceType") { value("OperationOutcome") }
            jsonPath("$.issue[0].code") { value("forbidden") }
        }
    }

    @Test
    fun `fhir document reference compartment search returns bundle in both patient forms`() {
        val correlationId = "fhir-doc-search-${UUID.randomUUID()}"
        val member = createMember(MembershipRole.CLINICIAN, "user/DocumentReference.read")
        val patient = createPatient(member.organization)
        val encounter = createEncounter(member.organization, patient)
        val note = createNote(member.organization, patient, encounter)

        mockMvc.get("/fhir/r4/DocumentReference") {
            param("patient", patient.id.value.toString())
            header("Authorization", "Bearer ${member.token}")
            header("X-Correlation-Id", correlationId)
        }.andExpect {
            status { isOk() }
            jsonPath("$.resourceType") { value("Bundle") }
            jsonPath("$.type") { value("searchset") }
            jsonPath("$.total") { value(1) }
            jsonPath("$.entry[0].resource.id") { value(note.id.value.toString()) }
            jsonPath("$.entry[0].search.mode") { value("match") }
        }

        val audit = auditRow(correlationId)
        assertEquals("SEARCH", audit.operation)
        assertEquals("SUCCESS", audit.outcome)

        mockMvc.get("/fhir/r4/DocumentReference") {
            param("patient", "Patient/${patient.id.value}")
            header("Authorization", "Bearer ${member.token}")
        }.andExpect {
            status { isOk() }
            jsonPath("$.total") { value(1) }
        }
    }

    @Test
    fun `fhir document reference search errors`() {
        val member = createMember(MembershipRole.CLINICIAN, "user/DocumentReference.read")
        val otherOrganization = createOrganization()
        val otherPatient = createPatient(otherOrganization)

        mockMvc.get("/fhir/r4/DocumentReference") {
            header("Authorization", "Bearer ${member.token}")
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.resourceType") { value("OperationOutcome") }
            jsonPath("$.issue[0].code") { value("invalid") }
        }

        mockMvc.get("/fhir/r4/DocumentReference") {
            param("patient", otherPatient.id.value.toString())
            header("Authorization", "Bearer ${member.token}")
        }.andExpect {
            status { isNotFound() }
            jsonPath("$.resourceType") { value("OperationOutcome") }
        }
    }

    private fun createOrganization(): Organization {
        val suffix = UUID.randomUUID()
        return organizationRepository.create(
            slug = "fhir-doc-org-$suffix",
            displayName = "Fhir Doc Org $suffix",
        )
    }

    private fun createMember(
        role: MembershipRole,
        scopes: String,
    ): FhirMemberFixture {
        val suffix = UUID.randomUUID()
        val organization = createOrganization()
        val user = userRepository.create(
            externalSubject = "fhir-doc-user-$suffix",
            email = "fhir-doc-user-$suffix@example.test",
            displayName = "Fhir Doc User $suffix",
        )
        val membership = membershipRepository.create(
            organizationId = organization.id,
            userId = user.id,
        )
        membershipRepository.addRole(membership.id, role)

        return FhirMemberFixture(
            organization = organization,
            user = user,
            token = DevJwtFactory(jwtEncoder).tokenFor(
                user = user,
                organization = organization,
                scopes = scopes,
            ),
        )
    }

    private fun createPatient(organization: Organization): Patient =
        patientRepository.create(
            PatientCreateCommand(
                organizationId = organization.id,
                givenName = "Synthetic",
                familyName = "Patient",
            ),
        )

    private fun createEncounter(
        organization: Organization,
        patient: Patient,
    ): Encounter =
        encounterRepository.create(
            EncounterCreateCommand(
                organizationId = organization.id,
                patientId = patient.id,
                classConceptId = classConcept.id,
                periodStart = Instant.parse("2026-06-01T09:00:00Z"),
            ),
        )

    private fun createNote(
        organization: Organization,
        patient: Patient,
        encounter: Encounter,
    ): ClinicalNote =
        clinicalNoteRepository.create(
            ClinicalNoteCreateCommand(
                organizationId = organization.id,
                patientId = patient.id,
                encounterId = encounter.id,
                typeConceptId = noteTypeConcept.id,
                title = "Progress note",
                contentText = "Synthetic note body.",
            ),
        )

    private fun auditCount(correlationId: String): Int =
        jdbcTemplate.queryForObject(
            "select count(*) from audit_events where correlation_id = ?",
            Int::class.java,
            correlationId,
        )!!

    private fun auditRow(correlationId: String): FhirEncounterAuditRow {
        assertEquals(1, auditCount(correlationId), "expected exactly one audit row for $correlationId")
        return jdbcTemplate.queryForObject(
            """
            select
              patient_id::text,
              resource_type,
              resource_id::text,
              operation,
              outcome,
              policy_reason_code
            from audit_events
            where correlation_id = ?
            """.trimIndent(),
            { rs, _ ->
                FhirEncounterAuditRow(
                    patientId = rs.getString("patient_id"),
                    resourceType = rs.getString("resource_type"),
                    resourceId = rs.getString("resource_id"),
                    operation = rs.getString("operation"),
                    outcome = rs.getString("outcome"),
                    policyReasonCode = rs.getString("policy_reason_code"),
                )
            },
            correlationId,
        )!!
    }
}
