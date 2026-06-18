package dev.ehr.fhir

import com.fasterxml.jackson.databind.ObjectMapper
import dev.ehr.identity.MembershipRepository
import dev.ehr.identity.MembershipRole
import dev.ehr.identity.Organization
import dev.ehr.identity.OrganizationRepository
import dev.ehr.identity.UserRepository
import dev.ehr.patient.PatientCreateCommand
import dev.ehr.patient.PatientRepository
import dev.ehr.testsupport.DevJwtFactory
import dev.ehr.testsupport.DevJwtTestConfiguration
import dev.ehr.testsupport.PostgresIntegrationTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.net.URI
import java.util.Base64
import java.util.UUID

/**
 * Slice AS4: the FHIR Bulk Data kickoff/status protocol over the Slice 8
 * export engine, authorized like every other export surface.
 */
@AutoConfigureMockMvc
@Import(DevJwtTestConfiguration::class)
class BulkExportFhirIntegrationTest : PostgresIntegrationTest() {
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
    lateinit var jwtEncoder: JwtEncoder

    @Autowired
    lateinit var objectMapper: ObjectMapper

    @Test
    fun `a backend service completes the bulk export protocol end to end`() {
        val fixture = createFixture()
        patientRepository.create(
            PatientCreateCommand(
                organizationId = fixture.organization.id,
                givenName = "Synthetic",
                familyName = "Patient",
            ),
        )
        val token = systemToken(fixture)

        // Kickoff: async preference required, _type refused, then 202.
        mockMvc.get("/fhir/r4/\$export") {
            header("Authorization", "Bearer $token")
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.resourceType") { value("OperationOutcome") }
            jsonPath("$.issue[0].code") { value("invalid") }
        }
        mockMvc.get("/fhir/r4/\$export") {
            header("Authorization", "Bearer $token")
            header("Prefer", "respond-async")
            param("_type", "Patient")
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.issue[0].code") { value("not-supported") }
        }
        mockMvc.get("/fhir/r4/\$export") {
            header("Authorization", "Bearer $token")
            header("Prefer", "respond-async")
            param("_since", "2026-01-01T00:00:00Z")
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.issue[0].code") { value("not-supported") }
        }

        val kickoff = mockMvc.get("/fhir/r4/\$export") {
            header("Authorization", "Bearer $token")
            header("Prefer", "respond-async")
        }.andExpect {
            status { isAccepted() }
            header { exists("Content-Location") }
        }.andReturn()
        val statusUrl = URI.create(kickoff.response.getHeader("Content-Location")!!)

        // Poll until the manifest appears.
        var manifestJson: String? = null
        var attempts = 0
        while (manifestJson == null && attempts < 40) {
            attempts++
            val response = mockMvc.get(statusUrl) {
                header("Authorization", "Bearer $token")
            }.andReturn().response
            when (response.status) {
                200 -> manifestJson = response.contentAsString
                202 -> {
                    assertNotNull(response.getHeader("X-Progress"))
                    Thread.sleep(250)
                }
                else -> throw AssertionError("unexpected export status ${response.status}: ${response.contentAsString}")
            }
        }
        val manifest = objectMapper.readTree(
            manifestJson ?: throw AssertionError("export did not complete in time"),
        )

        assertEquals(true, manifest["requiresAccessToken"].asBoolean())
        assertTrue(manifest["request"].asText().endsWith("/fhir/r4/\$export"))
        assertEquals(0, manifest["error"].size())
        val patientOutput = manifest["output"].single { it["type"].asText() == "Patient" }
        assertEquals(1, patientOutput["count"].asInt())

        // The manifest URLs actually serve NDJSON to the same token.
        mockMvc.get(URI.create(patientOutput["url"].asText())) {
            header("Authorization", "Bearer $token")
        }.andExpect {
            status { isOk() }
            content { contentTypeCompatibleWith("application/fhir+ndjson") }
            content { string(org.hamcrest.Matchers.containsString("\"resourceType\":\"Patient\"")) }
        }

        // Cancel is a recorded gap: the unmapped method is refused.
        mockMvc.delete(statusUrl) {
            header("Authorization", "Bearer $token")
        }.andExpect {
            status { isMethodNotAllowed() }
        }
    }

    @Test
    fun `bulk export is role gated and authenticated`() {
        val fixture = createFixture()
        val staffToken = staffToken(fixture)

        mockMvc.get("/fhir/r4/\$export") {
            header("Prefer", "respond-async")
        }.andExpect {
            status { isUnauthorized() }
        }

        mockMvc.get("/fhir/r4/\$export") {
            header("Authorization", "Bearer $staffToken")
            header("Prefer", "respond-async")
        }.andExpect {
            status { isForbidden() }
            jsonPath("$.resourceType") { value("OperationOutcome") }
        }
    }

    private fun systemToken(fixture: BulkExportFixture): String {
        val identifier = "bulk-app-${UUID.randomUUID()}"
        val registration = objectMapper.readTree(
            mockMvc.post("/api/v1/oauth-clients") {
                contentType = MediaType.APPLICATION_JSON
                content = """
                    {
                      "clientIdentifier": "$identifier",
                      "displayName": "Synthetic Bulk Service",
                      "clientType": "SYSTEM",
                      "grantedScopes": "system/*.read system/*.write"
                    }
                """.trimIndent()
                header("Authorization", "Bearer ${fixture.adminToken}")
            }.andExpect {
                status { isCreated() }
            }.andReturn().response.contentAsString,
        )
        val secret = registration["clientSecret"].asText()
        val tokenResponse = objectMapper.readTree(
            mockMvc.post("/oauth/token") {
                header(
                    "Authorization",
                    "Basic " + Base64.getEncoder().encodeToString("$identifier:$secret".toByteArray()),
                )
                contentType = MediaType.APPLICATION_FORM_URLENCODED
                content = "grant_type=client_credentials&scope=system/*.read%20system/*.write"
            }.andExpect {
                status { isOk() }
            }.andReturn().response.contentAsString,
        )
        return tokenResponse["access_token"].asText()
    }

    private fun staffToken(fixture: BulkExportFixture): String {
        val suffix = UUID.randomUUID()
        val staff = userRepository.create(
            externalSubject = "bulk-staff-$suffix",
            email = "bulk-staff-$suffix@example.test",
            displayName = "Bulk Staff $suffix",
        )
        val membership = membershipRepository.create(
            organizationId = fixture.organization.id,
            userId = staff.id,
        )
        membershipRepository.addRole(membership.id, MembershipRole.STAFF)
        return DevJwtFactory(jwtEncoder).tokenFor(
            user = staff,
            organization = fixture.organization,
            scopes = "user/*.read user/*.write",
        )
    }

    private fun createFixture(): BulkExportFixture {
        val suffix = UUID.randomUUID()
        val organization = organizationRepository.create(
            slug = "bulk-org-$suffix",
            displayName = "Bulk Org $suffix",
        )
        val admin = userRepository.create(
            externalSubject = "bulk-admin-$suffix",
            email = "bulk-admin-$suffix@example.test",
            displayName = "Bulk Admin $suffix",
        )
        val membership = membershipRepository.create(
            organizationId = organization.id,
            userId = admin.id,
        )
        membershipRepository.addRole(membership.id, MembershipRole.ORG_ADMIN)

        return BulkExportFixture(
            organization = organization,
            adminToken = DevJwtFactory(jwtEncoder).tokenFor(
                user = admin,
                organization = organization,
                scopes = "user/*.read user/*.write",
            ),
        )
    }
}

data class BulkExportFixture(
    val organization: Organization,
    val adminToken: String,
)
