package dev.ehr.note

import dev.ehr.encounter.Encounter
import dev.ehr.encounter.EncounterCreateCommand
import dev.ehr.encounter.EncounterRepository
import dev.ehr.identity.MembershipRepository
import dev.ehr.identity.MembershipRole
import dev.ehr.identity.Organization
import dev.ehr.identity.OrganizationRepository
import dev.ehr.identity.User
import dev.ehr.identity.UserRepository
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
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.time.Instant
import java.util.UUID

@AutoConfigureMockMvc
@Import(DevJwtTestConfiguration::class)
class ClinicalNoteApiIntegrationTest : PostgresIntegrationTest() {
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
    fun `note endpoints reject unauthenticated requests without audit`() {
        val correlationId = "note-unauth-${UUID.randomUUID()}"

        mockMvc.get("/api/v1/notes/${UUID.randomUUID()}") {
            header("X-Correlation-Id", correlationId)
        }.andExpect {
            status { isUnauthorized() }
        }

        assertEquals(0, auditCount(correlationId))
    }

    @Test
    fun `clinician can write a note on an encounter and the create is audited`() {
        val correlationId = "note-write-${UUID.randomUUID()}"
        val member = createMember(MembershipRole.CLINICIAN, "user/DocumentReference.write user/DocumentReference.read")
        val patient = createPatient(member.organization)
        val encounter = createEncounter(member.organization, patient)

        mockMvc.post("/api/v1/encounters/${encounter.id.value}/notes") {
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "typeConceptId": "${noteTypeConcept.id.value}",
                  "title": "Progress note",
                  "contentText": "Synthetic patient seen for routine follow-up. No acute findings."
                }
            """.trimIndent()
            header("Authorization", "Bearer ${member.token}")
            header("X-Correlation-Id", correlationId)
        }.andExpect {
            status { isCreated() }
            jsonPath("$.id") { exists() }
            jsonPath("$.patientId") { value(patient.id.value.toString()) }
            jsonPath("$.encounterId") { value(encounter.id.value.toString()) }
            jsonPath("$.status") { value("current") }
            jsonPath("$.typeConceptId") { value(noteTypeConcept.id.value.toString()) }
            jsonPath("$.title") { value("Progress note") }
            jsonPath("$.version") { value(1) }
        }

        val audit = auditRow(correlationId)
        assertEquals("NOTE", audit.resourceType)
        assertEquals("CREATE", audit.operation)
        assertEquals("SUCCESS", audit.outcome)
        assertEquals(patient.id.value.toString(), audit.patientId)
        assertEquals("policy-spine-v17", audit.policyVersion)
    }

    @Test
    fun `writing a note on another organizations encounter returns 404`() {
        val member = createMember(MembershipRole.CLINICIAN, "user/DocumentReference.write")
        val otherOrganization = createOrganization()
        val otherPatient = createPatient(otherOrganization)
        val otherEncounter = createEncounter(otherOrganization, otherPatient)

        mockMvc.post("/api/v1/encounters/${otherEncounter.id.value}/notes") {
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "typeConceptId": "${noteTypeConcept.id.value}",
                  "title": "Progress note",
                  "contentText": "Should not be written."
                }
            """.trimIndent()
            header("Authorization", "Bearer ${member.token}")
        }.andExpect {
            status { isNotFound() }
        }

        assertEquals(0, noteCount(otherOrganization))
    }

    @Test
    fun `staff cannot write notes and the denial is audited`() {
        val correlationId = "note-staff-${UUID.randomUUID()}"
        val member = createMember(MembershipRole.STAFF, "user/*.write user/*.read")
        val patient = createPatient(member.organization)
        val encounter = createEncounter(member.organization, patient)

        mockMvc.post("/api/v1/encounters/${encounter.id.value}/notes") {
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "typeConceptId": "${noteTypeConcept.id.value}",
                  "title": "Progress note",
                  "contentText": "Should be denied."
                }
            """.trimIndent()
            header("Authorization", "Bearer ${member.token}")
            header("X-Correlation-Id", correlationId)
        }.andExpect {
            status { isForbidden() }
        }

        val audit = auditRow(correlationId)
        assertEquals("AUTHORIZATION_DENIED", audit.operation)
        assertEquals("INSUFFICIENT_ROLE", audit.policyReasonCode)
        assertEquals(0, noteCount(member.organization))
    }

    @Test
    fun `clinician can read a note and list patient notes with audit`() {
        val readCorrelationId = "note-read-${UUID.randomUUID()}"
        val listCorrelationId = "note-list-${UUID.randomUUID()}"
        val member = createMember(MembershipRole.CLINICIAN, "user/DocumentReference.read")
        val patient = createPatient(member.organization)
        val encounter = createEncounter(member.organization, patient)
        val note = createNote(member.organization, patient, encounter)

        mockMvc.get("/api/v1/notes/${note.id.value}") {
            header("Authorization", "Bearer ${member.token}")
            header("X-Correlation-Id", readCorrelationId)
        }.andExpect {
            status { isOk() }
            jsonPath("$.id") { value(note.id.value.toString()) }
            jsonPath("$.contentText") { value(note.contentText) }
        }

        val readAudit = auditRow(readCorrelationId)
        assertEquals("READ", readAudit.operation)
        assertEquals(patient.id.value.toString(), readAudit.patientId)

        mockMvc.get("/api/v1/patients/${patient.id.value}/notes") {
            header("Authorization", "Bearer ${member.token}")
            header("X-Correlation-Id", listCorrelationId)
        }.andExpect {
            status { isOk() }
            jsonPath("$.notes.length()") { value(1) }
        }

        val listAudit = auditRow(listCorrelationId)
        assertEquals("SEARCH", listAudit.operation)
    }

    @Test
    fun `cross organization note read returns 404 and audits a failed read`() {
        val correlationId = "note-cross-${UUID.randomUUID()}"
        val member = createMember(MembershipRole.CLINICIAN, "user/DocumentReference.read")
        val otherOrganization = createOrganization()
        val otherPatient = createPatient(otherOrganization)
        val otherEncounter = createEncounter(otherOrganization, otherPatient)
        val otherNote = createNote(otherOrganization, otherPatient, otherEncounter)

        mockMvc.get("/api/v1/notes/${otherNote.id.value}") {
            header("Authorization", "Bearer ${member.token}")
            header("X-Correlation-Id", correlationId)
        }.andExpect {
            status { isNotFound() }
        }

        val audit = auditRow(correlationId)
        assertEquals("READ", audit.operation)
        assertEquals("FAILURE", audit.outcome)
    }

    @Test
    fun `writing a note with unknown type concept returns 400`() {
        val member = createMember(MembershipRole.CLINICIAN, "user/DocumentReference.write")
        val patient = createPatient(member.organization)
        val encounter = createEncounter(member.organization, patient)

        mockMvc.post("/api/v1/encounters/${encounter.id.value}/notes") {
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "typeConceptId": "${UUID.randomUUID()}",
                  "title": "Progress note",
                  "contentText": "Body."
                }
            """.trimIndent()
            header("Authorization", "Bearer ${member.token}")
        }.andExpect {
            status { isBadRequest() }
        }

        assertEquals(0, noteCount(member.organization))
    }

    private fun createOrganization(): Organization {
        val suffix = UUID.randomUUID()
        return organizationRepository.create(
            slug = "note-api-org-$suffix",
            displayName = "Note Api Org $suffix",
        )
    }

    private fun createMember(
        role: MembershipRole,
        scopes: String,
    ): NoteMemberFixture {
        val suffix = UUID.randomUUID()
        val organization = createOrganization()
        val user = userRepository.create(
            externalSubject = "note-api-user-$suffix",
            email = "note-api-user-$suffix@example.test",
            displayName = "Note Api User $suffix",
        )
        val membership = membershipRepository.create(
            organizationId = organization.id,
            userId = user.id,
        )
        membershipRepository.addRole(membership.id, role)

        return NoteMemberFixture(
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

    private fun noteCount(organization: Organization): Int =
        jdbcTemplate.queryForObject(
            "select count(*) from clinical_notes where organization_id = ?",
            Int::class.java,
            organization.id.value,
        )!!

    private fun auditCount(correlationId: String): Int =
        jdbcTemplate.queryForObject(
            "select count(*) from audit_events where correlation_id = ?",
            Int::class.java,
            correlationId,
        )!!

    private fun auditRow(correlationId: String): NoteAuditRow {
        assertEquals(1, auditCount(correlationId), "expected exactly one audit row for $correlationId")
        return jdbcTemplate.queryForObject(
            """
            select
              patient_id::text,
              resource_type,
              resource_id::text,
              operation,
              outcome,
              policy_version,
              policy_reason_code
            from audit_events
            where correlation_id = ?
            """.trimIndent(),
            { rs, _ ->
                NoteAuditRow(
                    patientId = rs.getString("patient_id"),
                    resourceType = rs.getString("resource_type"),
                    resourceId = rs.getString("resource_id"),
                    operation = rs.getString("operation"),
                    outcome = rs.getString("outcome"),
                    policyVersion = rs.getString("policy_version"),
                    policyReasonCode = rs.getString("policy_reason_code"),
                )
            },
            correlationId,
        )!!
    }
}

data class NoteMemberFixture(
    val organization: Organization,
    val user: User,
    val token: String,
)

data class NoteAuditRow(
    val patientId: String?,
    val resourceType: String,
    val resourceId: String?,
    val operation: String,
    val outcome: String,
    val policyVersion: String?,
    val policyReasonCode: String?,
)
