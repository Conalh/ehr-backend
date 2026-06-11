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
import dev.ehr.terminology.CodeableConcept
import dev.ehr.terminology.CodeableConceptRepository
import dev.ehr.terminology.CodingRepository
import dev.ehr.testsupport.DevJwtFactory
import dev.ehr.testsupport.DevJwtTestConfiguration
import dev.ehr.testsupport.EncounterTestFixtures
import dev.ehr.testsupport.PostgresIntegrationTest
import dev.ehr.testsupport.TerminologyTestFixtures
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.containsString
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
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import java.util.UUID

/**
 * Slice H3: organizations flipped to `enforced` deny relationship-less
 * clinical-record access; break-glass rescues emergency reads with full
 * audit evidence; `off` skips evaluation. Default posture stays `shadow`
 * (covered by CompartmentShadowIntegrationTest).
 */
@AutoConfigureMockMvc
@Import(DevJwtTestConfiguration::class)
class CompartmentEnforcementIntegrationTest : PostgresIntegrationTest() {
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
    fun `enforced organizations deny clinical access without a relationship`() {
        val listCorrelationId = "enf-list-${UUID.randomUUID()}"
        val getCorrelationId = "enf-get-${UUID.randomUUID()}"
        val updateCorrelationId = "enf-update-${UUID.randomUUID()}"
        val member = createMember(MembershipRole.CLINICIAN)
        val stranger = createColleague(member.organization)
        val patient = createPatient(member.organization)
        setEnforcement(member.organization, "enforced")

        // Opening an encounter stays org-wide and grants the relationship.
        openEncounter(member, patient)
        val conditionId = createCondition(member, patient)

        // Related clinician keeps working.
        mockMvc.get("/api/v1/patients/${patient.id.value}/conditions") {
            header("Authorization", "Bearer ${member.token}")
        }.andExpect {
            status { isOk() }
        }

        // Stranger: list-by-patient denied at the pre-fetch evaluation.
        mockMvc.get("/api/v1/patients/${patient.id.value}/conditions") {
            header("Authorization", "Bearer ${stranger.token}")
            header("X-Correlation-Id", listCorrelationId)
        }.andExpect {
            status { isForbidden() }
        }
        val listAudit = auditRow(listCorrelationId)
        assertEquals("AUTHORIZATION_DENIED", listAudit.operation)
        assertEquals("NO_TREATMENT_RELATIONSHIP", listAudit.policyReasonCode)
        assertEquals("policy-spine-v17", listAudit.policyVersion)

        // Stranger: get-by-id denied at the post-fetch re-evaluation.
        mockMvc.get("/api/v1/conditions/$conditionId") {
            header("Authorization", "Bearer ${stranger.token}")
            header("X-Correlation-Id", getCorrelationId)
        }.andExpect {
            status { isForbidden() }
        }
        assertEquals("NO_TREATMENT_RELATIONSHIP", auditRow(getCorrelationId).policyReasonCode)

        // Stranger: in-transaction update denied; the denial audit row
        // survives the rollback.
        mockMvc.patch("/api/v1/conditions/$conditionId") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"clinicalStatus":"RESOLVED","expectedVersion":1}"""
            header("Authorization", "Bearer ${stranger.token}")
            header("X-Correlation-Id", updateCorrelationId)
        }.andExpect {
            status { isForbidden() }
        }
        assertEquals("NO_TREATMENT_RELATIONSHIP", auditRow(updateCorrelationId).policyReasonCode)

        // Demographics stay org-wide even when enforced.
        mockMvc.get("/api/v1/patients/${patient.id.value}") {
            header("Authorization", "Bearer ${stranger.token}")
        }.andExpect {
            status { isOk() }
        }
    }

    @Test
    fun `break-glass permits an emergency read with full audit evidence`() {
        val breakGlassCorrelationId = "enf-bg-${UUID.randomUUID()}"
        val member = createMember(MembershipRole.CLINICIAN)
        val stranger = createColleague(member.organization)
        val patient = createPatient(member.organization)
        setEnforcement(member.organization, "enforced")
        openEncounter(member, patient)

        mockMvc.get("/api/v1/patients/${patient.id.value}/chart") {
            header("Authorization", "Bearer ${stranger.token}")
            header("X-Break-Glass-Reason", "Unresponsive patient in the ED")
            header("X-Correlation-Id", breakGlassCorrelationId)
        }.andExpect {
            status { isOk() }
        }
        val audit = auditRow(breakGlassCorrelationId)
        assertEquals("SUCCESS", audit.outcome)
        assertEquals("break-glass", audit.relationshipBasis)
        assertEquals("ETREAT", audit.purposeOfUse)
        assertThat(audit.metadata, containsString("Unresponsive patient in the ED"))

        // A blank reason is no assertion: the read is denied.
        mockMvc.get("/api/v1/patients/${patient.id.value}/chart") {
            header("Authorization", "Bearer ${stranger.token}")
            header("X-Break-Glass-Reason", "   ")
        }.andExpect {
            status { isForbidden() }
        }

        // Break-glass never rescues writes.
        mockMvc.post("/api/v1/patients/${patient.id.value}/conditions") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"codeConceptId":"${conditionConcept().id.value}"}"""
            header("Authorization", "Bearer ${stranger.token}")
            header("X-Break-Glass-Reason", "Unresponsive patient in the ED")
        }.andExpect {
            status { isForbidden() }
        }
    }

    @Test
    fun `off organizations skip relationship evaluation`() {
        val listCorrelationId = "enf-off-${UUID.randomUUID()}"
        val member = createMember(MembershipRole.CLINICIAN)
        val patient = createPatient(member.organization)
        setEnforcement(member.organization, "off")
        openEncounter(member, patient)

        mockMvc.get("/api/v1/patients/${patient.id.value}/conditions") {
            header("Authorization", "Bearer ${member.token}")
            header("X-Correlation-Id", listCorrelationId)
        }.andExpect {
            status { isOk() }
        }
        // Despite a real relationship, off-mode records nothing.
        assertEquals(null, auditRow(listCorrelationId).relationshipBasis)
    }

    private fun setEnforcement(
        organization: Organization,
        mode: String,
    ) {
        jdbcTemplate.update(
            "update organizations set compartment_enforcement = ? where id = ?",
            mode,
            organization.id.value,
        )
    }

    private fun openEncounter(
        member: EnforcementMemberFixture,
        patient: Patient,
    ) {
        val classConcept = EncounterTestFixtures(codingRepository, codeableConceptRepository)
            .createEncounterClassConcept()
        mockMvc.post("/api/v1/patients/${patient.id.value}/encounters") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"classConceptId":"${classConcept.id.value}","periodStart":"2026-06-01T09:00:00Z"}"""
            header("Authorization", "Bearer ${member.token}")
        }.andExpect {
            status { isCreated() }
        }
    }

    private fun createCondition(
        member: EnforcementMemberFixture,
        patient: Patient,
    ): UUID {
        val response = mockMvc.post("/api/v1/patients/${patient.id.value}/conditions") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"codeConceptId":"${conditionConcept().id.value}"}"""
            header("Authorization", "Bearer ${member.token}")
        }.andExpect {
            status { isCreated() }
        }.andReturn().response.contentAsString
        return UUID.fromString(Regex("\"id\":\"([0-9a-f-]+)\"").find(response)!!.groupValues[1])
    }

    private fun conditionConcept(): CodeableConcept =
        TerminologyTestFixtures(codingRepository, codeableConceptRepository)
            .findOrCreateConcept(
                system = CanonicalCodeSystems.SNOMED_CT,
                code = "38341003",
                display = "Hypertensive disorder",
            )

    private fun createColleague(organization: Organization): EnforcementMemberFixture {
        val suffix = UUID.randomUUID()
        val user = userRepository.create(
            externalSubject = "enf-colleague-$suffix",
            email = "enf-colleague-$suffix@example.test",
            displayName = "Enforcement Colleague $suffix",
        )
        val membership = membershipRepository.create(
            organizationId = organization.id,
            userId = user.id,
        )
        membershipRepository.addRole(membership.id, MembershipRole.CLINICIAN)
        return EnforcementMemberFixture(
            organization = organization,
            user = user,
            token = DevJwtFactory(jwtEncoder).tokenFor(
                user = user,
                organization = organization,
                scopes = "user/*.read user/*.write",
            ),
        )
    }

    private fun createMember(role: MembershipRole): EnforcementMemberFixture {
        val suffix = UUID.randomUUID()
        val organization = organizationRepository.create(
            slug = "enf-org-$suffix",
            displayName = "Enforcement Org $suffix",
        )
        val user = userRepository.create(
            externalSubject = "enf-user-$suffix",
            email = "enf-user-$suffix@example.test",
            displayName = "Enforcement User $suffix",
        )
        val membership = membershipRepository.create(
            organizationId = organization.id,
            userId = user.id,
        )
        membershipRepository.addRole(membership.id, role)

        return EnforcementMemberFixture(
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

    private fun auditRow(correlationId: String): EnforcementAuditRow {
        val count = jdbcTemplate.queryForObject(
            "select count(*) from audit_events where correlation_id = ?",
            Int::class.java,
            correlationId,
        )!!
        assertEquals(1, count, "expected exactly one audit row for $correlationId")
        return jdbcTemplate.queryForObject(
            """
            select operation, outcome, policy_version, policy_reason_code,
                   relationship_basis, purpose_of_use, metadata::text as metadata
            from audit_events
            where correlation_id = ?
            """.trimIndent(),
            { rs, _ ->
                EnforcementAuditRow(
                    operation = rs.getString("operation"),
                    outcome = rs.getString("outcome"),
                    policyVersion = rs.getString("policy_version"),
                    policyReasonCode = rs.getString("policy_reason_code"),
                    relationshipBasis = rs.getString("relationship_basis"),
                    purposeOfUse = rs.getString("purpose_of_use"),
                    metadata = rs.getString("metadata"),
                )
            },
            correlationId,
        )!!
    }
}

data class EnforcementMemberFixture(
    val organization: Organization,
    val user: User,
    val token: String,
)

data class EnforcementAuditRow(
    val operation: String,
    val outcome: String,
    val policyVersion: String?,
    val policyReasonCode: String?,
    val relationshipBasis: String?,
    val purposeOfUse: String?,
    val metadata: String,
)
