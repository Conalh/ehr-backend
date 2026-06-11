package dev.ehr.security

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
import dev.ehr.terminology.CodeableConceptRepository
import dev.ehr.terminology.CodingRepository
import dev.ehr.testsupport.DevJwtFactory
import dev.ehr.testsupport.DevJwtTestConfiguration
import dev.ehr.testsupport.EncounterTestFixtures
import dev.ehr.testsupport.PostgresIntegrationTest
import dev.ehr.testsupport.TerminologyTestFixtures
import org.junit.jupiter.api.Assertions.assertEquals
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
import java.util.UUID

/**
 * Slice H2: compartment evaluation runs in shadow mode. Every clinical-record
 * audit row records what relationship (if any) connected the user to the
 * patient — and nothing is denied for lacking one.
 */
@AutoConfigureMockMvc
@Import(DevJwtTestConfiguration::class)
class CompartmentShadowIntegrationTest : PostgresIntegrationTest() {
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
    lateinit var codingRepository: CodingRepository

    @Autowired
    lateinit var codeableConceptRepository: CodeableConceptRepository

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    lateinit var jwtEncoder: JwtEncoder

    @Test
    fun `opening an encounter shadows subsequent clinical reads as encounter-derived`() {
        val listCorrelationId = "shadow-list-${UUID.randomUUID()}"
        val getCorrelationId = "shadow-get-${UUID.randomUUID()}"
        val patientCorrelationId = "shadow-patient-${UUID.randomUUID()}"
        val member = createMember(MembershipRole.CLINICIAN)
        val patient = createPatient(member.organization)
        val classConcept = EncounterTestFixtures(codingRepository, codeableConceptRepository)
            .createEncounterClassConcept()
        val conditionConcept = TerminologyTestFixtures(codingRepository, codeableConceptRepository)
            .findOrCreateConcept(
                system = CanonicalCodeSystems.SNOMED_CT,
                code = "38341003",
                display = "Hypertensive disorder",
            )

        mockMvc.post("/api/v1/patients/${patient.id.value}/encounters") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"classConceptId":"${classConcept.id.value}","periodStart":"2026-06-01T09:00:00Z"}"""
            header("Authorization", "Bearer ${member.token}")
        }.andExpect {
            status { isCreated() }
        }

        // List-by-patient: the compartment patient is known before evaluation.
        mockMvc.get("/api/v1/patients/${patient.id.value}/conditions") {
            header("Authorization", "Bearer ${member.token}")
            header("X-Correlation-Id", listCorrelationId)
        }.andExpect {
            status { isOk() }
        }
        val listAudit = auditRow(listCorrelationId)
        assertEquals("SUCCESS", listAudit.outcome)
        assertEquals("encounter-derived", listAudit.relationshipBasis)
        assertEquals("policy-spine-v18", listAudit.policyVersion)

        // Get-by-id: the patient is only discovered after the fetch.
        val response = mockMvc.post("/api/v1/patients/${patient.id.value}/conditions") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"codeConceptId":"${conditionConcept.id.value}"}"""
            header("Authorization", "Bearer ${member.token}")
        }.andExpect {
            status { isCreated() }
        }.andReturn().response.contentAsString
        val conditionId = UUID.fromString(Regex("\"id\":\"([0-9a-f-]+)\"").find(response)!!.groupValues[1])

        mockMvc.get("/api/v1/conditions/$conditionId") {
            header("Authorization", "Bearer ${member.token}")
            header("X-Correlation-Id", getCorrelationId)
        }.andExpect {
            status { isOk() }
        }
        assertEquals("encounter-derived", auditRow(getCorrelationId).relationshipBasis)

        // Patient demographics stay org-wide: no basis even with a relationship.
        mockMvc.get("/api/v1/patients/${patient.id.value}") {
            header("Authorization", "Bearer ${member.token}")
            header("X-Correlation-Id", patientCorrelationId)
        }.andExpect {
            status { isOk() }
        }
        assertEquals(null, auditRow(patientCorrelationId).relationshipBasis)
    }

    @Test
    fun `explicit membership shadows chart reads as care-team-member`() {
        val chartCorrelationId = "shadow-chart-${UUID.randomUUID()}"
        val member = createMember(MembershipRole.CLINICIAN)
        val colleague = createColleague(member.organization)
        val patient = createPatient(member.organization)

        mockMvc.post("/api/v1/patients/${patient.id.value}/care-team") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"userId":"${colleague.user.id.value}","role":"COVERING"}"""
            header("Authorization", "Bearer ${member.token}")
        }.andExpect {
            status { isCreated() }
        }

        mockMvc.get("/api/v1/patients/${patient.id.value}/chart") {
            header("Authorization", "Bearer ${colleague.token}")
            header("X-Correlation-Id", chartCorrelationId)
        }.andExpect {
            status { isOk() }
        }
        val chartAudit = auditRow(chartCorrelationId)
        assertEquals("CHART", chartAudit.resourceType)
        assertEquals("care-team-member", chartAudit.relationshipBasis)
    }

    @Test
    fun `unrelated clinicians still read successfully with no relationship basis`() {
        val listCorrelationId = "shadow-none-${UUID.randomUUID()}"
        val member = createMember(MembershipRole.CLINICIAN)
        val stranger = createColleague(member.organization)
        val patient = createPatient(member.organization)

        // Shadow mode: allowed despite no encounter and no membership.
        mockMvc.get("/api/v1/patients/${patient.id.value}/conditions") {
            header("Authorization", "Bearer ${stranger.token}")
            header("X-Correlation-Id", listCorrelationId)
        }.andExpect {
            status { isOk() }
        }
        val audit = auditRow(listCorrelationId)
        assertEquals("SUCCESS", audit.outcome)
        assertEquals(null, audit.relationshipBasis)
    }

    private fun createColleague(organization: Organization): ShadowMemberFixture {
        val suffix = UUID.randomUUID()
        val user = userRepository.create(
            externalSubject = "shadow-colleague-$suffix",
            email = "shadow-colleague-$suffix@example.test",
            displayName = "Shadow Colleague $suffix",
        )
        val membership = membershipRepository.create(
            organizationId = organization.id,
            userId = user.id,
        )
        membershipRepository.addRole(membership.id, MembershipRole.CLINICIAN)
        return ShadowMemberFixture(
            organization = organization,
            user = user,
            token = DevJwtFactory(jwtEncoder).tokenFor(
                user = user,
                organization = organization,
                scopes = "user/*.read user/*.write",
            ),
        )
    }

    private fun createMember(role: MembershipRole): ShadowMemberFixture {
        val suffix = UUID.randomUUID()
        val organization = organizationRepository.create(
            slug = "shadow-org-$suffix",
            displayName = "Shadow Org $suffix",
        )
        val user = userRepository.create(
            externalSubject = "shadow-user-$suffix",
            email = "shadow-user-$suffix@example.test",
            displayName = "Shadow User $suffix",
        )
        val membership = membershipRepository.create(
            organizationId = organization.id,
            userId = user.id,
        )
        membershipRepository.addRole(membership.id, role)

        return ShadowMemberFixture(
            organization = organization,
            user = user,
            token = DevJwtFactory(jwtEncoder).tokenFor(
                user = user,
                organization = organization,
                scopes = "user/*.read user/*.write",
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

    private fun auditRow(correlationId: String): ShadowAuditRow {
        val count = jdbcTemplate.queryForObject(
            "select count(*) from audit_events where correlation_id = ?",
            Int::class.java,
            correlationId,
        )!!
        assertEquals(1, count, "expected exactly one audit row for $correlationId")
        return jdbcTemplate.queryForObject(
            """
            select resource_type, operation, outcome, relationship_basis, policy_version
            from audit_events
            where correlation_id = ?
            """.trimIndent(),
            { rs, _ ->
                ShadowAuditRow(
                    resourceType = rs.getString("resource_type"),
                    operation = rs.getString("operation"),
                    outcome = rs.getString("outcome"),
                    relationshipBasis = rs.getString("relationship_basis"),
                    policyVersion = rs.getString("policy_version"),
                )
            },
            correlationId,
        )!!
    }
}

data class ShadowMemberFixture(
    val organization: Organization,
    val user: User,
    val token: String,
)

data class ShadowAuditRow(
    val resourceType: String,
    val operation: String,
    val outcome: String,
    val relationshipBasis: String?,
    val policyVersion: String?,
)
