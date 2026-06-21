package dev.ehr.oauth

import dev.ehr.identity.MembershipRepository
import dev.ehr.identity.MembershipRole
import dev.ehr.identity.Organization
import dev.ehr.identity.OrganizationRepository
import dev.ehr.identity.User
import dev.ehr.identity.UserRepository
import dev.ehr.testsupport.DevJwtFactory
import dev.ehr.testsupport.DevJwtTestConfiguration
import dev.ehr.testsupport.PostgresIntegrationTest
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
class OAuthClientApiIntegrationTest : PostgresIntegrationTest() {
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
    fun `oauth client endpoints reject unauthenticated requests without audit`() {
        val correlationId = "client-unauth-${UUID.randomUUID()}"

        mockMvc.get("/api/v1/oauth-clients") {
            header("X-Correlation-Id", correlationId)
        }.andExpect {
            status { isUnauthorized() }
        }

        assertEquals(0, auditCount(correlationId))
    }

    @Test
    fun `org admin can register list read and revoke clients with audit`() {
        val registerCorrelationId = "client-register-${UUID.randomUUID()}"
        val revokeCorrelationId = "client-revoke-${UUID.randomUUID()}"
        val admin = createMember(MembershipRole.ORG_ADMIN, "user/*.read user/*.write")
        val identifier = "synthetic-app-${UUID.randomUUID()}"

        val response = mockMvc.post("/api/v1/oauth-clients") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"clientIdentifier":"$identifier","displayName":"Synthetic App"}"""
            header("Authorization", "Bearer ${admin.token}")
            header("X-Correlation-Id", registerCorrelationId)
        }.andExpect {
            status { isCreated() }
            jsonPath("$.clientIdentifier") { value(identifier) }
            jsonPath("$.displayName") { value("Synthetic App") }
            jsonPath("$.status") { value("active") }
            jsonPath("$.organizationId") { value(admin.organization.id.value.toString()) }
        }.andReturn().response.contentAsString
        val clientId = UUID.fromString(Regex("\"id\":\"([0-9a-f-]+)\"").find(response)!!.groupValues[1])

        val registerAudit = auditRow(registerCorrelationId)
        assertEquals("OAUTH_CLIENT", registerAudit.resourceType)
        assertEquals("CREATE", registerAudit.operation)
        assertEquals("SUCCESS", registerAudit.outcome)
        assertEquals("policy-spine-v20", registerAudit.policyVersion)

        mockMvc.get("/api/v1/oauth-clients") {
            header("Authorization", "Bearer ${admin.token}")
        }.andExpect {
            status { isOk() }
            jsonPath("$.clients.length()") { value(1) }
        }

        mockMvc.get("/api/v1/oauth-clients/$clientId") {
            header("Authorization", "Bearer ${admin.token}")
        }.andExpect {
            status { isOk() }
            jsonPath("$.id") { value(clientId.toString()) }
        }

        mockMvc.post("/api/v1/oauth-clients/$clientId/revoke") {
            header("Authorization", "Bearer ${admin.token}")
            header("X-Correlation-Id", revokeCorrelationId)
        }.andExpect {
            status { isOk() }
            jsonPath("$.status") { value("revoked") }
        }

        val revokeAudit = auditRow(revokeCorrelationId)
        assertEquals("UPDATE", revokeAudit.operation)
        assertEquals("SUCCESS", revokeAudit.outcome)

        // double revoke -> 422
        mockMvc.post("/api/v1/oauth-clients/$clientId/revoke") {
            header("Authorization", "Bearer ${admin.token}")
        }.andExpect {
            status { isUnprocessableEntity() }
        }
    }

    @Test
    fun `duplicate client identifier returns 409`() {
        val admin = createMember(MembershipRole.ORG_ADMIN, "user/*.write user/*.read")
        val identifier = "dup-app-${UUID.randomUUID()}"

        mockMvc.post("/api/v1/oauth-clients") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"clientIdentifier":"$identifier","displayName":"App One"}"""
            header("Authorization", "Bearer ${admin.token}")
        }.andExpect {
            status { isCreated() }
        }
        mockMvc.post("/api/v1/oauth-clients") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"clientIdentifier":"$identifier","displayName":"App Two"}"""
            header("Authorization", "Bearer ${admin.token}")
        }.andExpect {
            status { isConflict() }
        }
    }

    @Test
    fun `registration rejects smart scope contexts incompatible with client type`() {
        val admin = createMember(MembershipRole.ORG_ADMIN, "user/*.write user/*.read")

        mockMvc.post("/api/v1/oauth-clients") {
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "clientIdentifier": "system-bad-${UUID.randomUUID()}",
                  "displayName": "Bad System App",
                  "clientType": "SYSTEM",
                  "grantedScopes": "user/*.read"
                }
            """.trimIndent()
            header("Authorization", "Bearer ${admin.token}")
        }.andExpect {
            status { isBadRequest() }
        }

        mockMvc.post("/api/v1/oauth-clients") {
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "clientIdentifier": "public-bad-${UUID.randomUUID()}",
                  "displayName": "Bad User App",
                  "clientType": "PUBLIC",
                  "grantedScopes": "system/*.read",
                  "redirectUris": "https://app.example.test/callback"
                }
            """.trimIndent()
            header("Authorization", "Bearer ${admin.token}")
        }.andExpect {
            status { isBadRequest() }
        }
    }

    @Test
    fun `clinicians cannot manage clients and cross tenant reads fail closed`() {
        val deniedCorrelationId = "client-denied-${UUID.randomUUID()}"
        val admin = createMember(MembershipRole.ORG_ADMIN, "user/*.read user/*.write")
        val clinician = createMember(MembershipRole.CLINICIAN, "user/*.read user/*.write")
        val identifier = "tenant-app-${UUID.randomUUID()}"

        val response = mockMvc.post("/api/v1/oauth-clients") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"clientIdentifier":"$identifier","displayName":"Tenant App"}"""
            header("Authorization", "Bearer ${admin.token}")
        }.andExpect {
            status { isCreated() }
        }.andReturn().response.contentAsString
        val clientId = UUID.fromString(Regex("\"id\":\"([0-9a-f-]+)\"").find(response)!!.groupValues[1])

        mockMvc.post("/api/v1/oauth-clients") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"clientIdentifier":"x-${UUID.randomUUID()}","displayName":"X"}"""
            header("Authorization", "Bearer ${clinician.token}")
            header("X-Correlation-Id", deniedCorrelationId)
        }.andExpect {
            status { isForbidden() }
        }
        val deniedAudit = auditRow(deniedCorrelationId)
        assertEquals("AUTHORIZATION_DENIED", deniedAudit.operation)
        assertEquals("INSUFFICIENT_ROLE", deniedAudit.policyReasonCode)

        // cross-tenant read by another org's admin
        val otherAdmin = createMember(MembershipRole.ORG_ADMIN, "user/*.read user/*.write")
        mockMvc.get("/api/v1/oauth-clients/$clientId") {
            header("Authorization", "Bearer ${otherAdmin.token}")
        }.andExpect {
            status { isNotFound() }
        }
    }

    private fun createMember(
        role: MembershipRole,
        scopes: String,
    ): ClientMemberFixture {
        val suffix = UUID.randomUUID()
        val organization = organizationRepository.create(
            slug = "client-org-$suffix",
            displayName = "Client Org $suffix",
        )
        val user = userRepository.create(
            externalSubject = "client-user-$suffix",
            email = "client-user-$suffix@example.test",
            displayName = "Client User $suffix",
        )
        val membership = membershipRepository.create(
            organizationId = organization.id,
            userId = user.id,
        )
        membershipRepository.addRole(membership.id, role)

        return ClientMemberFixture(
            organization = organization,
            user = user,
            token = DevJwtFactory(jwtEncoder).tokenFor(
                user = user,
                organization = organization,
                scopes = scopes,
            ),
        )
    }

    private fun auditCount(correlationId: String): Int =
        jdbcTemplate.queryForObject(
            "select count(*) from audit_events where correlation_id = ?",
            Int::class.java,
            correlationId,
        )!!

    private fun auditRow(correlationId: String): ClientAuditRow {
        assertEquals(1, auditCount(correlationId), "expected exactly one audit row for $correlationId")
        return jdbcTemplate.queryForObject(
            """
            select resource_type, operation, outcome, policy_version, policy_reason_code
            from audit_events
            where correlation_id = ?
            """.trimIndent(),
            { rs, _ ->
                ClientAuditRow(
                    resourceType = rs.getString("resource_type"),
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

data class ClientMemberFixture(
    val organization: Organization,
    val user: User,
    val token: String,
)

data class ClientAuditRow(
    val resourceType: String,
    val operation: String,
    val outcome: String,
    val policyVersion: String?,
    val policyReasonCode: String?,
)
