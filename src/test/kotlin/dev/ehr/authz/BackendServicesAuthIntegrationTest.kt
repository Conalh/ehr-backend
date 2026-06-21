package dev.ehr.authz

import com.fasterxml.jackson.databind.ObjectMapper
import dev.ehr.identity.MembershipRepository
import dev.ehr.identity.MembershipRole
import dev.ehr.identity.Organization
import dev.ehr.identity.OrganizationRepository
import dev.ehr.identity.UserRepository
import dev.ehr.testsupport.DevJwtFactory
import dev.ehr.testsupport.DevJwtTestConfiguration
import dev.ehr.testsupport.PostgresIntegrationTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
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
import java.util.Base64
import java.util.UUID

/**
 * Slice AS1: the embedded authorization server issues client-credentials
 * tokens for registered system clients; SYSTEM_APP principals may run
 * exports and nothing else.
 */
@AutoConfigureMockMvc
@Import(DevJwtTestConfiguration::class)
class BackendServicesAuthIntegrationTest : PostgresIntegrationTest() {
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

    @Autowired
    lateinit var objectMapper: ObjectMapper

    @Test
    fun `system client registration returns the secret exactly once`() {
        val admin = createAdmin()
        val identifier = "system-app-${UUID.randomUUID()}"

        val response = registerSystemClient(admin, identifier)
        val secret = response["clientSecret"].asText()
        assertNotNull(secret)
        assertTrue(secret.length >= 32)
        assertEquals("system", response["clientType"].asText())
        val clientId = response["id"].asText()

        // The secret never appears again, and only its hash is stored.
        mockMvc.get("/api/v1/oauth-clients/$clientId") {
            header("Authorization", "Bearer ${admin.token}")
        }.andExpect {
            status { isOk() }
            jsonPath("$.clientSecret") { doesNotExist() }
        }
        val storedHash = jdbcTemplate.queryForObject(
            "select secret_hash from oauth_clients where id = ?::uuid",
            String::class.java,
            clientId,
        )!!
        assertTrue(storedHash.startsWith("\$argon2"))
        assertTrue(!storedHash.contains(secret))

        // Public clients get no secret.
        val publicResponse = objectMapper.readTree(
            mockMvc.post("/api/v1/oauth-clients") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"clientIdentifier":"public-${UUID.randomUUID()}","displayName":"Public App"}"""
                header("Authorization", "Bearer ${admin.token}")
            }.andExpect {
                status { isCreated() }
            }.andReturn().response.contentAsString,
        )
        assertNull(publicResponse["clientSecret"]?.takeIf { !it.isNull })
    }

    @Test
    fun `client credentials tokens authorize exports and nothing clinical`() {
        val admin = createAdmin()
        val identifier = "system-app-${UUID.randomUUID()}"
        val registration = registerSystemClient(admin, identifier)
        val secret = registration["clientSecret"].asText()

        val token = requestToken(identifier, secret)

        // Export kickoff: SYSTEM_APP + system wildcard scopes.
        val exportCorrelationId = "as1-export-${UUID.randomUUID()}"
        mockMvc.post("/api/v1/export-jobs") {
            header("Authorization", "Bearer $token")
            header("X-Correlation-Id", exportCorrelationId)
        }.andExpect {
            status { isAccepted() }
        }
        val audit = jdbcTemplate.queryForMap(
            """
            select resource_type, operation, outcome, subject_user_id, policy_version
            from audit_events where correlation_id = ?
            """.trimIndent(),
            exportCorrelationId,
        )
        assertEquals("EXPORT", audit["resource_type"])
        assertEquals("SUCCESS", audit["outcome"])
        assertNull(audit["subject_user_id"])
        assertEquals("policy-spine-v20", audit["policy_version"])

        // Clinical reads are denied by role.
        val deniedCorrelationId = "as1-denied-${UUID.randomUUID()}"
        mockMvc.get("/api/v1/patients/${UUID.randomUUID()}/conditions") {
            header("Authorization", "Bearer $token")
            header("X-Correlation-Id", deniedCorrelationId)
        }.andExpect {
            status { isForbidden() }
        }
        val deniedReason = jdbcTemplate.queryForObject(
            "select policy_reason_code from audit_events where correlation_id = ?",
            String::class.java,
            deniedCorrelationId,
        )
        assertEquals("INSUFFICIENT_ROLE", deniedReason)
    }

    @Test
    fun `bad secrets revoked clients and public clients cannot obtain tokens`() {
        val admin = createAdmin()
        val identifier = "system-app-${UUID.randomUUID()}"
        val registration = registerSystemClient(admin, identifier)
        val secret = registration["clientSecret"].asText()
        val clientId = registration["id"].asText()

        // Wrong secret.
        mockMvc.post("/oauth/token") {
            header("Authorization", basicAuth(identifier, "wrong-secret"))
            contentType = MediaType.APPLICATION_FORM_URLENCODED
            content = "grant_type=client_credentials"
        }.andExpect {
            status { isUnauthorized() }
        }
        mockMvc.post("/oauth/token") {
            contentType = MediaType.APPLICATION_FORM_URLENCODED
            content = "grant_type=client_credentials&client_id=$identifier&client_secret=$secret"
        }.andExpect {
            status { isUnauthorized() }
        }

        // Public client: not registered for the grant at all.
        val publicIdentifier = "public-${UUID.randomUUID()}"
        mockMvc.post("/api/v1/oauth-clients") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"clientIdentifier":"$publicIdentifier","displayName":"Public App"}"""
            header("Authorization", "Bearer ${admin.token}")
        }.andExpect {
            status { isCreated() }
        }
        mockMvc.post("/oauth/token") {
            header("Authorization", basicAuth(publicIdentifier, "anything"))
            contentType = MediaType.APPLICATION_FORM_URLENCODED
            content = "grant_type=client_credentials"
        }.andExpect {
            status { isUnauthorized() }
        }

        // Live tokens die with the client: revoke, then the existing token
        // fails identity resolution.
        val token = requestToken(identifier, secret)
        mockMvc.post("/api/v1/oauth-clients/$clientId/revoke") {
            header("Authorization", "Bearer ${admin.token}")
        }.andExpect {
            status { isOk() }
        }
        mockMvc.post("/api/v1/export-jobs") {
            header("Authorization", "Bearer $token")
        }.andExpect {
            status { isUnauthorized() }
        }
        // And no new tokens are issued.
        mockMvc.post("/oauth/token") {
            header("Authorization", basicAuth(identifier, secret))
            contentType = MediaType.APPLICATION_FORM_URLENCODED
            content = "grant_type=client_credentials"
        }.andExpect {
            status { isUnauthorized() }
        }
    }

    @Test
    fun `the jwks endpoint is public`() {
        mockMvc.get("/oauth/jwks")
            .andExpect {
                status { isOk() }
                jsonPath("$.keys[0].kty") { value("RSA") }
            }
    }

    private fun requestToken(
        identifier: String,
        secret: String,
    ): String {
        val response = mockMvc.post("/oauth/token") {
            header("Authorization", basicAuth(identifier, secret))
            contentType = MediaType.APPLICATION_FORM_URLENCODED
            content = "grant_type=client_credentials&scope=system/*.read%20system/*.write"
        }.andExpect {
            status { isOk() }
            jsonPath("$.access_token") { exists() }
            jsonPath("$.token_type") { value("Bearer") }
        }.andReturn().response.contentAsString
        return objectMapper.readTree(response)["access_token"].asText()
    }

    private fun registerSystemClient(
        admin: AdminFixture,
        identifier: String,
    ) = objectMapper.readTree(
        mockMvc.post("/api/v1/oauth-clients") {
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "clientIdentifier": "$identifier",
                  "displayName": "Synthetic Backend Service",
                  "clientType": "SYSTEM",
                  "grantedScopes": "system/*.read system/*.write"
                }
            """.trimIndent()
            header("Authorization", "Bearer ${admin.token}")
        }.andExpect {
            status { isCreated() }
        }.andReturn().response.contentAsString,
    )!!

    private fun basicAuth(
        user: String,
        password: String,
    ): String =
        "Basic " + Base64.getEncoder().encodeToString("$user:$password".toByteArray())

    private fun createAdmin(): AdminFixture {
        val suffix = UUID.randomUUID()
        val organization = organizationRepository.create(
            slug = "as1-org-$suffix",
            displayName = "As1 Org $suffix",
        )
        val user = userRepository.create(
            externalSubject = "as1-admin-$suffix",
            email = "as1-admin-$suffix@example.test",
            displayName = "As1 Admin $suffix",
        )
        val membership = membershipRepository.create(
            organizationId = organization.id,
            userId = user.id,
        )
        membershipRepository.addRole(membership.id, MembershipRole.ORG_ADMIN)

        return AdminFixture(
            organization = organization,
            token = DevJwtFactory(jwtEncoder).tokenFor(
                user = user,
                organization = organization,
                scopes = "user/*.read user/*.write",
            ),
        )
    }
}

data class AdminFixture(
    val organization: Organization,
    val token: String,
)
