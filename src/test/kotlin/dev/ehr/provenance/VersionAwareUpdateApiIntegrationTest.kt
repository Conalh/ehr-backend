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
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import java.util.UUID

@AutoConfigureMockMvc
@Import(DevJwtTestConfiguration::class)
class VersionAwareUpdateApiIntegrationTest : PostgresIntegrationTest() {
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

    lateinit var snomedConcept: CodeableConcept
    lateinit var classConcept: CodeableConcept

    @BeforeEach
    fun setUpConcepts() {
        snomedConcept = TerminologyTestFixtures(codingRepository, codeableConceptRepository)
            .findOrCreateConcept(
                system = CanonicalCodeSystems.SNOMED_CT,
                code = "38341003",
                display = "Hypertensive disorder",
            )
        classConcept = EncounterTestFixtures(codingRepository, codeableConceptRepository)
            .createEncounterClassConcept()
    }

    @Test
    fun `resolving a condition increments version captures a revision and records updated provenance`() {
        val member = createMember()
        val scope = TenantScope(member.organization.id)
        val patientId = createPatient(member)
        val conditionId = postForId(
            member,
            "/api/v1/patients/$patientId/conditions",
            """{"codeConceptId":"${snomedConcept.id.value}","onsetDate":"2026-01-15"}""",
        )

        mockMvc.patch("/api/v1/conditions/$conditionId") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"expectedVersion":1,"clinicalStatus":"RESOLVED","abatementDate":"2026-05-01"}"""
            header("Authorization", "Bearer ${member.token}")
        }.andExpect {
            status { isOk() }
            jsonPath("$.clinicalStatus") { value("resolved") }
            jsonPath("$.abatementDate") { value("2026-05-01") }
            jsonPath("$.version") { value(2) }
        }

        val revisions = resourceRevisionRepository.findRevisions(scope, "CONDITION", conditionId)
        assertEquals(1, revisions.size)
        assertEquals(1, revisions[0].version)
        assertTrue(revisions[0].snapshotJson.contains("ACTIVE", ignoreCase = true))

        val events = provenanceRepository.findByTarget(scope, "CONDITION", conditionId)
        assertEquals(2, events.size)
        assertEquals(ProvenanceActivity.UPDATED, events.last().activity)
        assertEquals(1, events.last().priorResourceVersion)
        assertEquals(2, events.last().targetVersion)
        assertEquals(member.user.id, events.last().agentUserId)
    }

    @Test
    fun `voiding a condition records entered in error provenance`() {
        val member = createMember()
        val scope = TenantScope(member.organization.id)
        val patientId = createPatient(member)
        val conditionId = postForId(
            member,
            "/api/v1/patients/$patientId/conditions",
            """{"codeConceptId":"${snomedConcept.id.value}"}""",
        )

        mockMvc.patch("/api/v1/conditions/$conditionId") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"expectedVersion":1,"verificationStatus":"ENTERED_IN_ERROR"}"""
            header("Authorization", "Bearer ${member.token}")
        }.andExpect {
            status { isOk() }
            jsonPath("$.verificationStatus") { value("entered-in-error") }
        }

        val events = provenanceRepository.findByTarget(scope, "CONDITION", conditionId)
        assertEquals(ProvenanceActivity.ENTERED_IN_ERROR, events.last().activity)
    }

    @Test
    fun `stale condition update returns 409 and leaves no revision`() {
        val member = createMember()
        val scope = TenantScope(member.organization.id)
        val patientId = createPatient(member)
        val conditionId = postForId(
            member,
            "/api/v1/patients/$patientId/conditions",
            """{"codeConceptId":"${snomedConcept.id.value}"}""",
        )

        mockMvc.patch("/api/v1/conditions/$conditionId") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"expectedVersion":99,"clinicalStatus":"RESOLVED"}"""
            header("Authorization", "Bearer ${member.token}")
        }.andExpect {
            status { isConflict() }
        }

        assertEquals(0, resourceRevisionRepository.findRevisions(scope, "CONDITION", conditionId).size)
        assertEquals(1, provenanceRepository.findByTarget(scope, "CONDITION", conditionId).size)
    }

    @Test
    fun `amending an observation replaces the value forces amended status and builds a revision chain`() {
        val member = createMember()
        val scope = TenantScope(member.organization.id)
        val patientId = createPatient(member)
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

        mockMvc.post("/api/v1/observations/$observationId/amend") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"expectedVersion":1,"valueQuantity":{"value":76,"unit":"/min"}}"""
            header("Authorization", "Bearer ${member.token}")
        }.andExpect {
            status { isOk() }
            jsonPath("$.status") { value("amended") }
            jsonPath("$.value.quantity") { value(76) }
            jsonPath("$.version") { value(2) }
        }

        // second amendment switches value shape entirely
        mockMvc.post("/api/v1/observations/$observationId/amend") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"expectedVersion":2,"valueText":"unmeasurable"}"""
            header("Authorization", "Bearer ${member.token}")
        }.andExpect {
            status { isOk() }
            jsonPath("$.value.text") { value("unmeasurable") }
            jsonPath("$.version") { value(3) }
        }

        val revisions = resourceRevisionRepository.findRevisions(scope, "OBSERVATION", observationId)
        assertEquals(listOf(1, 2), revisions.map { it.version })

        val events = provenanceRepository.findByTarget(scope, "OBSERVATION", observationId)
        assertEquals(3, events.size)
        assertEquals(
            listOf(ProvenanceActivity.CREATED, ProvenanceActivity.AMENDED, ProvenanceActivity.AMENDED),
            events.map { it.activity },
        )
    }

    @Test
    fun `amending a note updates content and records amended provenance`() {
        val member = createMember()
        val scope = TenantScope(member.organization.id)
        val patientId = createPatient(member)
        val encounterId = postForId(
            member,
            "/api/v1/patients/$patientId/encounters",
            """{"classConceptId":"${classConcept.id.value}","periodStart":"2026-06-01T09:00:00Z"}""",
        )
        val noteId = postForId(
            member,
            "/api/v1/encounters/$encounterId/notes",
            """{"typeConceptId":"${snomedConcept.id.value}","title":"Progress note","contentText":"Original body."}""",
        )

        mockMvc.patch("/api/v1/notes/$noteId") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"expectedVersion":1,"contentText":"Amended body with correction."}"""
            header("Authorization", "Bearer ${member.token}")
        }.andExpect {
            status { isOk() }
            jsonPath("$.contentText") { value("Amended body with correction.") }
            jsonPath("$.title") { value("Progress note") }
            jsonPath("$.version") { value(2) }
        }

        val revisions = resourceRevisionRepository.findRevisions(scope, "NOTE", noteId)
        assertEquals(1, revisions.size)
        assertTrue(revisions[0].snapshotJson.contains("Original body."))

        val events = provenanceRepository.findByTarget(scope, "NOTE", noteId)
        assertEquals(ProvenanceActivity.AMENDED, events.last().activity)
        assertEquals(1, events.last().priorResourceVersion)
    }

    @Test
    fun `update validation and tenancy failures`() {
        val member = createMember()
        val patientId = createPatient(member)
        val conditionId = postForId(
            member,
            "/api/v1/patients/$patientId/conditions",
            """{"codeConceptId":"${snomedConcept.id.value}","onsetDate":"2026-05-01"}""",
        )

        // inverted dates
        mockMvc.patch("/api/v1/conditions/$conditionId") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"expectedVersion":1,"abatementDate":"2026-01-01"}"""
            header("Authorization", "Bearer ${member.token}")
        }.andExpect {
            status { isBadRequest() }
        }

        // missing expectedVersion
        mockMvc.patch("/api/v1/conditions/$conditionId") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"clinicalStatus":"RESOLVED"}"""
            header("Authorization", "Bearer ${member.token}")
        }.andExpect {
            status { isBadRequest() }
        }

        // cross-tenant update
        val outsider = createMember()
        mockMvc.patch("/api/v1/conditions/$conditionId") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"expectedVersion":1,"clinicalStatus":"RESOLVED"}"""
            header("Authorization", "Bearer ${outsider.token}")
        }.andExpect {
            status { isNotFound() }
        }

        // staff denied
        val staff = createMember(role = MembershipRole.STAFF)
        mockMvc.patch("/api/v1/conditions/$conditionId") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"expectedVersion":1,"clinicalStatus":"RESOLVED"}"""
            header("Authorization", "Bearer ${staff.token}")
        }.andExpect {
            status { isForbidden() }
        }
    }

    private fun createPatient(member: UpdateMemberFixture): UUID =
        postForId(
            member,
            "/api/v1/patients",
            """{"givenName":"Synthetic","familyName":"Patient"}""",
        )

    private fun postForId(
        member: UpdateMemberFixture,
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

    private fun createMember(role: MembershipRole = MembershipRole.CLINICIAN): UpdateMemberFixture {
        val suffix = UUID.randomUUID()
        val organization = organizationRepository.create(
            slug = "update-org-$suffix",
            displayName = "Update Org $suffix",
        )
        val user = userRepository.create(
            externalSubject = "update-user-$suffix",
            email = "update-user-$suffix@example.test",
            displayName = "Update User $suffix",
        )
        val membership = membershipRepository.create(
            organizationId = organization.id,
            userId = user.id,
        )
        membershipRepository.addRole(membership.id, role)

        return UpdateMemberFixture(
            organization = organization,
            user = user,
            token = DevJwtFactory(jwtEncoder).tokenFor(
                user = user,
                organization = organization,
                scopes = "user/*.read user/*.write",
            ),
        )
    }
}

data class UpdateMemberFixture(
    val organization: Organization,
    val user: User,
    val token: String,
)
