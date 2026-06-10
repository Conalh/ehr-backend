package dev.ehr.security

import dev.ehr.identity.MembershipRepository
import dev.ehr.identity.MembershipRole
import dev.ehr.identity.OrganizationRepository
import dev.ehr.identity.UserRepository
import dev.ehr.testsupport.DevJwtFactory
import dev.ehr.testsupport.DevJwtTestConfiguration
import dev.ehr.testsupport.PostgresIntegrationTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import java.util.UUID

@AutoConfigureMockMvc
@Import(DevJwtTestConfiguration::class)
class PolicyDecisionEndpointIntegrationTest : PostgresIntegrationTest() {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var organizationRepository: OrganizationRepository

    @Autowired
    lateinit var userRepository: UserRepository

    @Autowired
    lateinit var membershipRepository: MembershipRepository

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    lateinit var jwtEncoder: JwtEncoder

    @Test
    fun `policy check rejects unauthenticated requests`() {
        val correlationId = "policy-unauth-${UUID.randomUUID()}"

        mockMvc.get("/api/v1/security/policy-check") {
            header("X-Correlation-Id", correlationId)
        }
            .andExpect {
                status { isUnauthorized() }
            }

        assertEquals(0, auditEventCountByCorrelationId(correlationId))
    }

    @Test
    fun `policy check returns allowed decision for admin member with compatible scope`() {
        val correlationId = "policy-allow-${UUID.randomUUID()}"
        val fixture = createAuthenticatedMember(
            role = MembershipRole.ORG_ADMIN,
            scopes = "user/*.read",
        )

        mockMvc.get("/api/v1/security/policy-check") {
            header("Authorization", "Bearer ${fixture.token}")
            header("X-Correlation-Id", correlationId)
        }.andExpect {
            status { isOk() }
            jsonPath("$.allowed") { value(true) }
            jsonPath("$.subjectUserId") { value(fixture.userId) }
            jsonPath("$.organizationId") { value(fixture.organizationId) }
            jsonPath("$.membershipId") { value(fixture.membershipId) }
            jsonPath("$.resourceType") { value("ORGANIZATION") }
            jsonPath("$.operation") { value("READ") }
            jsonPath("$.roleBasis[0]") { value("ORG_ADMIN") }
            jsonPath("$.scopeBasis[0].rawValue") { value("user/*.read") }
            jsonPath("$.relationshipBasis") { doesNotExist() }
            jsonPath("$.purposeOfUse") { doesNotExist() }
            jsonPath("$.policyVersion") { value("policy-spine-v4") }
            jsonPath("$.reasonCode") { value("ALLOWED") }
        }

        val auditRow = auditEventByCorrelationId(correlationId)
        assertEquals(fixture.organizationId, auditRow.organizationId)
        assertEquals(fixture.userId, auditRow.subjectUserId)
        assertEquals(null, auditRow.clientId)
        assertEquals("ORGANIZATION", auditRow.resourceType)
        assertEquals("READ", auditRow.operation)
        assertEquals("SUCCESS", auditRow.outcome)
        assertEquals("policy-spine-v4", auditRow.policyVersion)
        assertEquals("ALLOWED", auditRow.policyReasonCode)
        assertEquals(correlationId, auditRow.correlationId)
        assertEquals("{}", auditRow.metadata)
    }

    @Test
    fun `policy check returns denied decision for member without enough role`() {
        val correlationId = "policy-deny-role-${UUID.randomUUID()}"
        val fixture = createAuthenticatedMember(
            role = MembershipRole.STAFF,
            scopes = "user/*.read",
        )

        mockMvc.get("/api/v1/security/policy-check") {
            header("Authorization", "Bearer ${fixture.token}")
            header("X-Correlation-Id", correlationId)
        }.andExpect {
            status { isOk() }
            jsonPath("$.allowed") { value(false) }
            jsonPath("$.roleBasis[0]") { value("STAFF") }
            jsonPath("$.scopeBasis[0].rawValue") { value("user/*.read") }
            jsonPath("$.reasonCode") { value("INSUFFICIENT_ROLE") }
        }

        val auditRow = auditEventByCorrelationId(correlationId)
        assertEquals(fixture.organizationId, auditRow.organizationId)
        assertEquals(fixture.userId, auditRow.subjectUserId)
        assertEquals("ORGANIZATION", auditRow.resourceType)
        assertEquals("AUTHORIZATION_DENIED", auditRow.operation)
        assertEquals("DENIED", auditRow.outcome)
        assertEquals("policy-spine-v4", auditRow.policyVersion)
        assertEquals("INSUFFICIENT_ROLE", auditRow.policyReasonCode)
        assertEquals(correlationId, auditRow.correlationId)
    }

    @Test
    fun `policy check returns denied decision for admin member without compatible scope`() {
        val correlationId = "policy-deny-scope-${UUID.randomUUID()}"
        val fixture = createAuthenticatedMember(
            role = MembershipRole.ORG_ADMIN,
            scopes = "patient/Patient.rs",
        )

        mockMvc.get("/api/v1/security/policy-check") {
            header("Authorization", "Bearer ${fixture.token}")
            header("X-Correlation-Id", correlationId)
        }.andExpect {
            status { isOk() }
            jsonPath("$.allowed") { value(false) }
            jsonPath("$.roleBasis[0]") { value("ORG_ADMIN") }
            jsonPath("$.scopeBasis") { isEmpty() }
            jsonPath("$.reasonCode") { value("INSUFFICIENT_SCOPE") }
        }

        val auditRow = auditEventByCorrelationId(correlationId)
        assertEquals(fixture.organizationId, auditRow.organizationId)
        assertEquals(fixture.userId, auditRow.subjectUserId)
        assertEquals("AUTHORIZATION_DENIED", auditRow.operation)
        assertEquals("DENIED", auditRow.outcome)
        assertEquals("INSUFFICIENT_SCOPE", auditRow.policyReasonCode)
    }

    @Test
    fun `policy check audits authenticated organization mismatch decisions`() {
        val correlationId = "policy-deny-org-${UUID.randomUUID()}"
        val fixture = createAuthenticatedMember(
            role = MembershipRole.ORG_ADMIN,
            scopes = "user/*.read",
        )
        val otherOrganization = organizationRepository.create(
            slug = "policy-other-org-${UUID.randomUUID()}",
            displayName = "Policy Other Org",
        )

        mockMvc.get("/api/v1/security/policy-check") {
            param("organizationId", otherOrganization.id.value.toString())
            header("Authorization", "Bearer ${fixture.token}")
            header("X-Correlation-Id", correlationId)
        }.andExpect {
            status { isOk() }
            jsonPath("$.allowed") { value(false) }
            jsonPath("$.organizationId") { value(otherOrganization.id.value.toString()) }
            jsonPath("$.roleBasis") { isEmpty() }
            jsonPath("$.scopeBasis") { isEmpty() }
            jsonPath("$.reasonCode") { value("ORGANIZATION_MISMATCH") }
        }

        val auditRow = auditEventByCorrelationId(correlationId)
        assertEquals(otherOrganization.id.value.toString(), auditRow.organizationId)
        assertEquals(fixture.userId, auditRow.subjectUserId)
        assertEquals("ORGANIZATION", auditRow.resourceType)
        assertEquals("AUTHORIZATION_DENIED", auditRow.operation)
        assertEquals("DENIED", auditRow.outcome)
        assertEquals("policy-spine-v4", auditRow.policyVersion)
        assertEquals("ORGANIZATION_MISMATCH", auditRow.policyReasonCode)
        assertEquals(correlationId, auditRow.correlationId)
        assertEquals("{}", auditRow.metadata)
    }

    private fun createAuthenticatedMember(
        role: MembershipRole,
        scopes: String,
    ): PolicyEndpointFixture {
        val suffix = UUID.randomUUID()
        val organization = organizationRepository.create(
            slug = "policy-org-$suffix",
            displayName = "Policy Org $suffix",
        )
        val user = userRepository.create(
            externalSubject = "policy-user-$suffix",
            email = "policy-user-$suffix@example.test",
            displayName = "Policy User $suffix",
        )
        val membership = membershipRepository.create(
            organizationId = organization.id,
            userId = user.id,
        )
        membershipRepository.addRole(membership.id, role)

        return PolicyEndpointFixture(
            token = DevJwtFactory(jwtEncoder).tokenFor(
                user = user,
                organization = organization,
                scopes = scopes,
            ),
            userId = user.id.value.toString(),
            organizationId = organization.id.value.toString(),
            membershipId = membership.id.value.toString(),
        )
    }

    private fun auditEventCountByCorrelationId(correlationId: String): Int =
        jdbcTemplate.queryForObject(
            "select count(*) from audit_events where correlation_id = ?",
            Int::class.java,
            correlationId,
        )!!

    private fun auditEventByCorrelationId(correlationId: String): PolicyAuditRow {
        assertEquals(1, auditEventCountByCorrelationId(correlationId))
        return jdbcTemplate.queryForObject(
            """
            select
              organization_id::text,
              subject_user_id::text,
              client_id::text,
              resource_type,
              operation,
              outcome,
              policy_version,
              policy_reason_code,
              correlation_id,
              metadata::text
            from audit_events
            where correlation_id = ?
            """.trimIndent(),
            { rs, _ ->
                PolicyAuditRow(
                    organizationId = rs.getString("organization_id"),
                    subjectUserId = rs.getString("subject_user_id"),
                    clientId = rs.getString("client_id"),
                    resourceType = rs.getString("resource_type"),
                    operation = rs.getString("operation"),
                    outcome = rs.getString("outcome"),
                    policyVersion = rs.getString("policy_version"),
                    policyReasonCode = rs.getString("policy_reason_code"),
                    correlationId = rs.getString("correlation_id"),
                    metadata = rs.getString("metadata"),
                )
            },
            correlationId,
        )!!
    }
}

data class PolicyEndpointFixture(
    val token: String,
    val userId: String,
    val organizationId: String,
    val membershipId: String,
)

data class PolicyAuditRow(
    val organizationId: String,
    val subjectUserId: String,
    val clientId: String?,
    val resourceType: String,
    val operation: String,
    val outcome: String,
    val policyVersion: String,
    val policyReasonCode: String,
    val correlationId: String,
    val metadata: String,
)
