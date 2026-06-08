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
        mockMvc.get("/api/v1/security/policy-check")
            .andExpect {
                status { isUnauthorized() }
            }
    }

    @Test
    fun `policy check returns allowed decision for admin member with compatible scope`() {
        val fixture = createAuthenticatedMember(
            role = MembershipRole.ORG_ADMIN,
            scopes = "user/*.read",
        )

        mockMvc.get("/api/v1/security/policy-check") {
            header("Authorization", "Bearer ${fixture.token}")
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
            jsonPath("$.policyVersion") { value("policy-spine-v1") }
            jsonPath("$.reasonCode") { value("ALLOWED") }
        }

        assertEquals(0, auditEventCount())
    }

    @Test
    fun `policy check returns denied decision for member without enough role`() {
        val fixture = createAuthenticatedMember(
            role = MembershipRole.STAFF,
            scopes = "user/*.read",
        )

        mockMvc.get("/api/v1/security/policy-check") {
            header("Authorization", "Bearer ${fixture.token}")
        }.andExpect {
            status { isOk() }
            jsonPath("$.allowed") { value(false) }
            jsonPath("$.roleBasis[0]") { value("STAFF") }
            jsonPath("$.scopeBasis[0].rawValue") { value("user/*.read") }
            jsonPath("$.reasonCode") { value("INSUFFICIENT_ROLE") }
        }

        assertEquals(0, auditEventCount())
    }

    @Test
    fun `policy check returns denied decision for admin member without compatible scope`() {
        val fixture = createAuthenticatedMember(
            role = MembershipRole.ORG_ADMIN,
            scopes = "patient/Patient.rs",
        )

        mockMvc.get("/api/v1/security/policy-check") {
            header("Authorization", "Bearer ${fixture.token}")
        }.andExpect {
            status { isOk() }
            jsonPath("$.allowed") { value(false) }
            jsonPath("$.roleBasis[0]") { value("ORG_ADMIN") }
            jsonPath("$.scopeBasis") { isEmpty() }
            jsonPath("$.reasonCode") { value("INSUFFICIENT_SCOPE") }
        }

        assertEquals(0, auditEventCount())
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

    private fun auditEventCount(): Int =
        jdbcTemplate.queryForObject("select count(*) from audit_events", Int::class.java)!!
}

data class PolicyEndpointFixture(
    val token: String,
    val userId: String,
    val organizationId: String,
    val membershipId: String,
)
