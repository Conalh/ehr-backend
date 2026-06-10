package dev.ehr.provenance

import dev.ehr.identity.MembershipRepository
import dev.ehr.identity.MembershipRole
import dev.ehr.identity.Organization
import dev.ehr.identity.OrganizationRepository
import dev.ehr.identity.TenantScope
import dev.ehr.identity.User
import dev.ehr.identity.UserRepository
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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import java.util.UUID

@AutoConfigureMockMvc
@Import(DevJwtTestConfiguration::class)
class ProvenanceIntegrationTest : PostgresIntegrationTest() {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var organizationRepository: OrganizationRepository

    @Autowired
    lateinit var userRepository: UserRepository

    @Autowired
    lateinit var membershipRepository: MembershipRepository

    @Autowired
    lateinit var provenanceRepository: ProvenanceRepository

    @Autowired
    lateinit var resourceRevisionRepository: ResourceRevisionRepository

    @Autowired
    lateinit var codingRepository: CodingRepository

    @Autowired
    lateinit var codeableConceptRepository: CodeableConceptRepository

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    lateinit var jwtEncoder: JwtEncoder

    lateinit var classConcept: CodeableConcept
    lateinit var snomedConcept: CodeableConcept

    @BeforeEach
    fun setUpConcepts() {
        classConcept = EncounterTestFixtures(codingRepository, codeableConceptRepository)
            .createEncounterClassConcept()
        snomedConcept = TerminologyTestFixtures(codingRepository, codeableConceptRepository)
            .findOrCreateConcept(
                system = CanonicalCodeSystems.SNOMED_CT,
                code = "38341003",
                display = "Hypertensive disorder",
            )
    }

    @Test
    fun `clinical creates record created provenance at version one with the acting clinician`() {
        val member = createMember(MembershipRole.CLINICIAN, "user/*.read user/*.write")
        val scope = TenantScope(member.organization.id)

        // patient create
        val patientId = postForId(
            member,
            "/api/v1/patients",
            """{"givenName":"Synthetic","familyName":"Patient"}""",
        )
        val patientProvenance = provenanceRepository.findByTarget(scope, "PATIENT", patientId).single()
        assertEquals(ProvenanceActivity.CREATED, patientProvenance.activity)
        assertEquals(1, patientProvenance.targetVersion)
        assertEquals(ProvenanceSourceType.CLINICIAN_AUTHORED, patientProvenance.sourceType)
        assertEquals(member.user.id, patientProvenance.agentUserId)
        assertEquals(patientId, patientProvenance.patientId)

        // encounter create
        val encounterId = postForId(
            member,
            "/api/v1/patients/$patientId/encounters",
            """{"classConceptId":"${classConcept.id.value}","periodStart":"2026-06-01T09:00:00Z"}""",
        )
        val encounterProvenance = provenanceRepository.findByTarget(scope, "ENCOUNTER", encounterId).single()
        assertEquals(ProvenanceActivity.CREATED, encounterProvenance.activity)
        assertEquals(patientId, encounterProvenance.patientId)

        // condition create
        val conditionId = postForId(
            member,
            "/api/v1/patients/$patientId/conditions",
            """{"codeConceptId":"${snomedConcept.id.value}"}""",
        )
        assertEquals(1, provenanceRepository.findByTarget(scope, "CONDITION", conditionId).size)

        // allergy create
        val allergyId = postForId(
            member,
            "/api/v1/patients/$patientId/allergies",
            """{"codeConceptId":"${snomedConcept.id.value}"}""",
        )
        assertEquals(1, provenanceRepository.findByTarget(scope, "ALLERGY", allergyId).size)

        // observation create
        val observationId = postForId(
            member,
            "/api/v1/patients/$patientId/observations",
            """
            {
              "codeConceptId": "${snomedConcept.id.value}",
              "category": "VITAL_SIGNS",
              "effectiveAt": "2026-06-01T09:30:00Z",
              "valueQuantity": {"value": 72, "unit": "/min"}
            }
            """.trimIndent(),
        )
        assertEquals(1, provenanceRepository.findByTarget(scope, "OBSERVATION", observationId).size)

        // medication statement create
        val medicationId = postForId(
            member,
            "/api/v1/patients/$patientId/medication-statements",
            """{"medicationConceptId":"${snomedConcept.id.value}"}""",
        )
        assertEquals(1, provenanceRepository.findByTarget(scope, "MEDICATION", medicationId).size)

        // note create
        val noteId = postForId(
            member,
            "/api/v1/encounters/$encounterId/notes",
            """
            {
              "typeConceptId": "${snomedConcept.id.value}",
              "title": "Progress note",
              "contentText": "Body."
            }
            """.trimIndent(),
        )
        assertEquals(1, provenanceRepository.findByTarget(scope, "NOTE", noteId).size)

        // compartment view sees all seven events
        val compartment = provenanceRepository.findByPatient(scope, patientId)
        assertEquals(7, compartment.size)
        assertTrue(compartment.all { it.activity == ProvenanceActivity.CREATED })
    }

    @Test
    fun `encounter transition records a prior state revision and updated provenance`() {
        val member = createMember(MembershipRole.CLINICIAN, "user/*.read user/*.write")
        val scope = TenantScope(member.organization.id)
        val patientId = postForId(
            member,
            "/api/v1/patients",
            """{"givenName":"Synthetic","familyName":"Patient"}""",
        )
        val encounterId = postForId(
            member,
            "/api/v1/patients/$patientId/encounters",
            """{"classConceptId":"${classConcept.id.value}","periodStart":"2026-06-01T09:00:00Z"}""",
        )

        mockMvc.post("/api/v1/encounters/$encounterId/status") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"targetStatus":"IN_PROGRESS"}"""
            header("Authorization", "Bearer ${member.token}")
        }.andExpect {
            status { isOk() }
        }

        val revisions = resourceRevisionRepository.findRevisions(scope, "ENCOUNTER", encounterId)
        assertEquals(1, revisions.size)
        assertEquals(1, revisions[0].version)
        assertEquals(patientId, revisions[0].patientId)
        assertEquals(member.user.id, revisions[0].recordedBy)
        assertTrue(revisions[0].snapshotJson.contains("\"PLANNED\"") || revisions[0].snapshotJson.contains("planned"))

        val events = provenanceRepository.findByTarget(scope, "ENCOUNTER", encounterId)
        assertEquals(2, events.size)
        val updated = events.last()
        assertEquals(ProvenanceActivity.UPDATED, updated.activity)
        assertEquals(2, updated.targetVersion)
        assertEquals(1, updated.priorResourceVersion)
    }

    @Test
    fun `failed transition leaves no revision or provenance`() {
        val member = createMember(MembershipRole.CLINICIAN, "user/*.read user/*.write")
        val scope = TenantScope(member.organization.id)
        val patientId = postForId(
            member,
            "/api/v1/patients",
            """{"givenName":"Synthetic","familyName":"Patient"}""",
        )
        val encounterId = postForId(
            member,
            "/api/v1/patients/$patientId/encounters",
            """{"classConceptId":"${classConcept.id.value}","periodStart":"2026-06-01T09:00:00Z"}""",
        )

        // planned -> finished is invalid
        mockMvc.post("/api/v1/encounters/$encounterId/status") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"targetStatus":"FINISHED","periodEnd":"2026-06-01T11:00:00Z"}"""
            header("Authorization", "Bearer ${member.token}")
        }.andExpect {
            status { isUnprocessableEntity() }
        }

        assertEquals(0, resourceRevisionRepository.findRevisions(scope, "ENCOUNTER", encounterId).size)
        assertEquals(1, provenanceRepository.findByTarget(scope, "ENCOUNTER", encounterId).size)
    }

    @Test
    fun `provenance and revision reads fail closed across organizations`() {
        val member = createMember(MembershipRole.CLINICIAN, "user/*.read user/*.write")
        val otherOrganization = createOrganizationOnly()
        val patientId = postForId(
            member,
            "/api/v1/patients",
            """{"givenName":"Synthetic","familyName":"Patient"}""",
        )

        val otherScope = TenantScope(otherOrganization.id)
        assertEquals(0, provenanceRepository.findByTarget(otherScope, "PATIENT", patientId).size)
        assertEquals(0, provenanceRepository.findByPatient(otherScope, patientId).size)
        assertEquals(0, resourceRevisionRepository.findRevisions(otherScope, "PATIENT", patientId).size)
    }

    private fun postForId(
        member: ProvenanceMemberFixture,
        path: String,
        body: String,
    ): UUID {
        val response = mockMvc.post(path) {
            contentType = MediaType.APPLICATION_JSON
            content = body
            header("Authorization", "Bearer ${member.token}")
        }.andExpect {
            status { isCreated() }
        }.andReturn().response.contentAsString
        return UUID.fromString(Regex("\"id\":\"([0-9a-f-]+)\"").find(response)!!.groupValues[1])
    }

    private fun createOrganizationOnly(): Organization {
        val suffix = UUID.randomUUID()
        return organizationRepository.create(
            slug = "prov-other-org-$suffix",
            displayName = "Prov Other Org $suffix",
        )
    }

    private fun createMember(
        role: MembershipRole,
        scopes: String,
    ): ProvenanceMemberFixture {
        val suffix = UUID.randomUUID()
        val organization = organizationRepository.create(
            slug = "prov-org-$suffix",
            displayName = "Prov Org $suffix",
        )
        val user = userRepository.create(
            externalSubject = "prov-user-$suffix",
            email = "prov-user-$suffix@example.test",
            displayName = "Prov User $suffix",
        )
        val membership = membershipRepository.create(
            organizationId = organization.id,
            userId = user.id,
        )
        membershipRepository.addRole(membership.id, role)

        return ProvenanceMemberFixture(
            organization = organization,
            user = user,
            token = DevJwtFactory(jwtEncoder).tokenFor(
                user = user,
                organization = organization,
                scopes = scopes,
            ),
        )
    }
}

data class ProvenanceMemberFixture(
    val organization: Organization,
    val user: User,
    val token: String,
)
