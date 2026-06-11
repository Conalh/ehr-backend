package dev.ehr.careteam

import dev.ehr.identity.MembershipRepository
import dev.ehr.identity.MembershipRole
import dev.ehr.identity.Organization
import dev.ehr.identity.OrganizationRepository
import dev.ehr.identity.TenantScope
import dev.ehr.identity.User
import dev.ehr.identity.UserRepository
import dev.ehr.patient.Patient
import dev.ehr.patient.PatientCreateCommand
import dev.ehr.patient.PatientRepository
import dev.ehr.testsupport.DevJwtFactory
import dev.ehr.testsupport.DevJwtTestConfiguration
import dev.ehr.testsupport.EncounterTestFixtures
import dev.ehr.testsupport.PostgresIntegrationTest
import dev.ehr.terminology.CodeableConceptRepository
import dev.ehr.terminology.CodingRepository
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

@AutoConfigureMockMvc
@Import(DevJwtTestConfiguration::class)
class CareTeamApiIntegrationTest : PostgresIntegrationTest() {
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
    lateinit var careTeamRepository: CareTeamRepository

    @Autowired
    lateinit var codingRepository: CodingRepository

    @Autowired
    lateinit var codeableConceptRepository: CodeableConceptRepository

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    lateinit var jwtEncoder: JwtEncoder

    @Test
    fun `clinician can add list and end explicit care team members with audit`() {
        val addCorrelationId = "ct-add-${UUID.randomUUID()}"
        val endCorrelationId = "ct-end-${UUID.randomUUID()}"
        val member = createMember(MembershipRole.CLINICIAN)
        val colleague = createUserInOrg(member.organization)
        val patient = createPatient(member.organization)

        val response = mockMvc.post("/api/v1/patients/${patient.id.value}/care-team") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"userId":"${colleague.id.value}","role":"ATTENDING"}"""
            header("Authorization", "Bearer ${member.token}")
            header("X-Correlation-Id", addCorrelationId)
        }.andExpect {
            status { isCreated() }
            jsonPath("$.patientId") { value(patient.id.value.toString()) }
            jsonPath("$.userId") { value(colleague.id.value.toString()) }
            jsonPath("$.role") { value("attending") }
            jsonPath("$.origin") { value("explicit") }
            jsonPath("$.periodEnd") { doesNotExist() }
        }.andReturn().response.contentAsString
        val membershipId = UUID.fromString(Regex("\"id\":\"([0-9a-f-]+)\"").find(response)!!.groupValues[1])

        val addAudit = auditRow(addCorrelationId)
        assertEquals("CARE_TEAM", addAudit.resourceType)
        assertEquals("CREATE", addAudit.operation)
        assertEquals(patient.id.value.toString(), addAudit.patientId)
        assertEquals("policy-spine-v17", addAudit.policyVersion)

        // duplicate active membership -> 409
        mockMvc.post("/api/v1/patients/${patient.id.value}/care-team") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"userId":"${colleague.id.value}","role":"ATTENDING"}"""
            header("Authorization", "Bearer ${member.token}")
        }.andExpect {
            status { isConflict() }
        }

        mockMvc.get("/api/v1/patients/${patient.id.value}/care-team") {
            header("Authorization", "Bearer ${member.token}")
        }.andExpect {
            status { isOk() }
            jsonPath("$.members.length()") { value(1) }
        }

        mockMvc.post("/api/v1/care-team-memberships/$membershipId/end") {
            header("Authorization", "Bearer ${member.token}")
            header("X-Correlation-Id", endCorrelationId)
        }.andExpect {
            status { isOk() }
            jsonPath("$.periodEnd") { exists() }
        }
        assertEquals("UPDATE", auditRow(endCorrelationId).operation)

        // ended membership disappears from the active list and can be re-added
        mockMvc.get("/api/v1/patients/${patient.id.value}/care-team") {
            header("Authorization", "Bearer ${member.token}")
        }.andExpect {
            status { isOk() }
            jsonPath("$.members.length()") { value(0) }
        }
        mockMvc.post("/api/v1/patients/${patient.id.value}/care-team") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"userId":"${colleague.id.value}","role":"ATTENDING"}"""
            header("Authorization", "Bearer ${member.token}")
        }.andExpect {
            status { isCreated() }
        }

        // double end -> 422
        mockMvc.post("/api/v1/care-team-memberships/$membershipId/end") {
            header("Authorization", "Bearer ${member.token}")
        }.andExpect {
            status { isUnprocessableEntity() }
        }
    }

    @Test
    fun `opening an encounter derives a care team membership exactly once`() {
        val member = createMember(MembershipRole.CLINICIAN)
        val patient = createPatient(member.organization)
        val classConcept = EncounterTestFixtures(codingRepository, codeableConceptRepository)
            .createEncounterClassConcept()

        repeat(2) {
            mockMvc.post("/api/v1/patients/${patient.id.value}/encounters") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"classConceptId":"${classConcept.id.value}","periodStart":"2026-06-01T09:00:00Z"}"""
                header("Authorization", "Bearer ${member.token}")
            }.andExpect {
                status { isCreated() }
            }
        }

        val memberships = careTeamRepository.findActiveByPatient(
            TenantScope(member.organization.id),
            patient.id,
        )
        assertEquals(1, memberships.size)
        assertEquals(member.user.id, memberships[0].userId)
        assertEquals(CareTeamMembershipOrigin.ENCOUNTER_DERIVED, memberships[0].origin)
        assertEquals(CareTeamRole.CARE_TEAM, memberships[0].role)
    }

    @Test
    fun `care team management is role gated and tenant isolated`() {
        val staffCorrelationId = "ct-staff-${UUID.randomUUID()}"
        val member = createMember(MembershipRole.CLINICIAN)
        val staff = createMember(MembershipRole.STAFF)
        val orgAdmin = createMember(MembershipRole.ORG_ADMIN)
        val outsider = createMember(MembershipRole.CLINICIAN)
        val patient = createPatient(member.organization)
        val staffPatient = createPatient(staff.organization)

        // staff denied
        mockMvc.get("/api/v1/patients/${staffPatient.id.value}/care-team") {
            header("Authorization", "Bearer ${staff.token}")
            header("X-Correlation-Id", staffCorrelationId)
        }.andExpect {
            status { isForbidden() }
        }
        assertEquals("INSUFFICIENT_ROLE", auditRow(staffCorrelationId).policyReasonCode)

        // org admin allowed (own org)
        val adminPatient = createPatient(orgAdmin.organization)
        mockMvc.get("/api/v1/patients/${adminPatient.id.value}/care-team") {
            header("Authorization", "Bearer ${orgAdmin.token}")
        }.andExpect {
            status { isOk() }
        }

        // cross-tenant list and add fail closed
        mockMvc.get("/api/v1/patients/${patient.id.value}/care-team") {
            header("Authorization", "Bearer ${outsider.token}")
        }.andExpect {
            status { isNotFound() }
        }
        mockMvc.post("/api/v1/patients/${patient.id.value}/care-team") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"userId":"${outsider.user.id.value}"}"""
            header("Authorization", "Bearer ${outsider.token}")
        }.andExpect {
            status { isNotFound() }
        }

        // unauthenticated
        mockMvc.get("/api/v1/patients/${patient.id.value}/care-team")
            .andExpect {
                status { isUnauthorized() }
            }
    }

    private fun createUserInOrg(organization: Organization): User {
        val suffix = UUID.randomUUID()
        val user = userRepository.create(
            externalSubject = "ct-colleague-$suffix",
            email = "ct-colleague-$suffix@example.test",
            displayName = "Care Team Colleague $suffix",
        )
        val membership = membershipRepository.create(
            organizationId = organization.id,
            userId = user.id,
        )
        membershipRepository.addRole(membership.id, MembershipRole.CLINICIAN)
        return user
    }

    private fun createMember(role: MembershipRole): CareTeamMemberFixture {
        val suffix = UUID.randomUUID()
        val organization = organizationRepository.create(
            slug = "ct-org-$suffix",
            displayName = "Care Team Org $suffix",
        )
        val user = userRepository.create(
            externalSubject = "ct-user-$suffix",
            email = "ct-user-$suffix@example.test",
            displayName = "Care Team User $suffix",
        )
        val membership = membershipRepository.create(
            organizationId = organization.id,
            userId = user.id,
        )
        membershipRepository.addRole(membership.id, role)

        return CareTeamMemberFixture(
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

    private fun auditRow(correlationId: String): CareTeamAuditRow {
        val count = jdbcTemplate.queryForObject(
            "select count(*) from audit_events where correlation_id = ?",
            Int::class.java,
            correlationId,
        )!!
        assertEquals(1, count, "expected exactly one audit row for $correlationId")
        return jdbcTemplate.queryForObject(
            """
            select resource_type, operation, outcome, patient_id::text, policy_version, policy_reason_code
            from audit_events
            where correlation_id = ?
            """.trimIndent(),
            { rs, _ ->
                CareTeamAuditRow(
                    resourceType = rs.getString("resource_type"),
                    operation = rs.getString("operation"),
                    outcome = rs.getString("outcome"),
                    patientId = rs.getString("patient_id"),
                    policyVersion = rs.getString("policy_version"),
                    policyReasonCode = rs.getString("policy_reason_code"),
                )
            },
            correlationId,
        )!!
    }
}

data class CareTeamMemberFixture(
    val organization: Organization,
    val user: User,
    val token: String,
)

data class CareTeamAuditRow(
    val resourceType: String,
    val operation: String,
    val outcome: String,
    val patientId: String?,
    val policyVersion: String?,
    val policyReasonCode: String?,
)
