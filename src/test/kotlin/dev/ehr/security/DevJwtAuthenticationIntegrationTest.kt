package dev.ehr.security

import dev.ehr.identity.OAuthClientId
import dev.ehr.identity.OrganizationRepository
import dev.ehr.identity.UserRepository
import dev.ehr.testsupport.DevJwtFactory
import dev.ehr.testsupport.DevJwtTestConfiguration
import dev.ehr.testsupport.PostgresIntegrationTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import java.util.UUID

@AutoConfigureMockMvc
@Import(DevJwtTestConfiguration::class)
class DevJwtAuthenticationIntegrationTest : PostgresIntegrationTest() {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var organizationRepository: OrganizationRepository

    @Autowired
    lateinit var userRepository: UserRepository

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    lateinit var jwtEncoder: JwtEncoder

    @Test
    fun `health endpoint remains public`() {
        mockMvc.get("/internal/health")
            .andExpect {
                status { isOk() }
                jsonPath("$.status") { value("UP") }
            }
    }

    @Test
    fun `protected placeholder endpoint rejects unauthenticated requests`() {
        mockMvc.get("/api/v1/security/whoami")
            .andExpect {
                status { isUnauthorized() }
            }
    }

    @Test
    fun `valid dev JWT authenticates and exposes principal context`() {
        val suffix = UUID.randomUUID()
        val organization = organizationRepository.create(
            slug = "auth-org-$suffix",
            displayName = "Auth Org $suffix",
        )
        val user = userRepository.create(
            externalSubject = "auth-user-$suffix",
            email = "auth-user-$suffix@example.test",
            displayName = "Auth User $suffix",
        )
        val clientId = OAuthClientId(UUID.randomUUID())
        val token = DevJwtFactory(jwtEncoder).tokenFor(
            user = user,
            organization = organization,
            scopes = "user/*.read patient/Patient.rs system/*.read",
            clientId = clientId,
        )

        mockMvc.get("/api/v1/security/whoami") {
            header("Authorization", "Bearer $token")
        }.andExpect {
            status { isOk() }
            jsonPath("$.externalSubject") { value(user.externalSubject) }
            jsonPath("$.userId") { value(user.id.value.toString()) }
            jsonPath("$.organizationId") { value(organization.id.value.toString()) }
            jsonPath("$.scopes[0]") { value("user/*.read") }
            jsonPath("$.scopes[1]") { value("patient/Patient.rs") }
            jsonPath("$.scopes[2]") { value("system/*.read") }
            jsonPath("$.clientId") { value(clientId.value.toString()) }
        }

        assertEquals(0, auditEventCount())
    }

    @Test
    fun `valid dev JWT can resolve organization by slug`() {
        val suffix = UUID.randomUUID()
        val organization = organizationRepository.create(
            slug = "slug-auth-org-$suffix",
            displayName = "Slug Auth Org $suffix",
        )
        val user = userRepository.create(
            externalSubject = "slug-auth-user-$suffix",
            email = "slug-auth-user-$suffix@example.test",
            displayName = "Slug Auth User $suffix",
        )
        val token = DevJwtFactory(jwtEncoder).token(
            subject = user.externalSubject,
            organizationSlug = organization.slug,
            scopes = "user/*.read",
        )

        mockMvc.get("/api/v1/security/whoami") {
            header("Authorization", "Bearer $token")
        }.andExpect {
            status { isOk() }
            jsonPath("$.organizationId") { value(organization.id.value.toString()) }
        }
    }

    @Test
    fun `unknown user subject is rejected`() {
        val suffix = UUID.randomUUID()
        val organization = organizationRepository.create(
            slug = "unknown-user-org-$suffix",
            displayName = "Unknown User Org $suffix",
        )
        val token = DevJwtFactory(jwtEncoder).token(
            subject = "missing-user-$suffix",
            organizationId = organization.id.value.toString(),
        )

        mockMvc.get("/api/v1/security/whoami") {
            header("Authorization", "Bearer $token")
        }.andExpect {
            status { isUnauthorized() }
        }

        assertEquals(0, auditEventCount())
    }

    @Test
    fun `unknown organization is rejected`() {
        val suffix = UUID.randomUUID()
        val user = userRepository.create(
            externalSubject = "unknown-org-user-$suffix",
            email = "unknown-org-user-$suffix@example.test",
            displayName = "Unknown Org User $suffix",
        )
        val token = DevJwtFactory(jwtEncoder).token(
            subject = user.externalSubject,
            organizationId = UUID.randomUUID().toString(),
        )

        mockMvc.get("/api/v1/security/whoami") {
            header("Authorization", "Bearer $token")
        }.andExpect {
            status { isUnauthorized() }
        }

        assertEquals(0, auditEventCount())
    }

    @Test
    fun `fhir path requires authentication by default`() {
        mockMvc.get("/fhir/r4/metadata")
            .andExpect {
                status { isUnauthorized() }
            }
    }

    private fun auditEventCount(): Int =
        jdbcTemplate.queryForObject("select count(*) from audit_events", Int::class.java)!!
}
