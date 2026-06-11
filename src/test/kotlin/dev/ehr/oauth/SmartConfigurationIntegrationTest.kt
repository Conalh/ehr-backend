package dev.ehr.oauth

import dev.ehr.testsupport.DevJwtTestConfiguration
import dev.ehr.testsupport.PostgresIntegrationTest
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.util.UUID

@AutoConfigureMockMvc
@Import(DevJwtTestConfiguration::class)
class SmartConfigurationIntegrationTest : PostgresIntegrationTest() {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Test
    fun `smart configuration is public and accurately describes capabilities`() {
        mockMvc.get("/.well-known/smart-configuration")
            .andExpect {
                status { isOk() }
                jsonPath("$.authorization_endpoint") { value(org.hamcrest.Matchers.endsWith("/oauth/authorize")) }
                jsonPath("$.token_endpoint") { value(org.hamcrest.Matchers.endsWith("/oauth/token")) }
                jsonPath("$.revocation_endpoint") { value(org.hamcrest.Matchers.endsWith("/oauth/revoke")) }
                jsonPath("$.grant_types_supported") {
                    value(
                        org.hamcrest.Matchers.containsInAnyOrder(
                            "authorization_code",
                            "refresh_token",
                            "client_credentials",
                        ),
                    )
                }
                jsonPath("$.code_challenge_methods_supported[0]") { value("S256") }
                jsonPath("$.scopes_supported") { value(org.hamcrest.Matchers.hasItems("user/*.read", "system/*.cruds", "openid")) }
                jsonPath("$.capabilities") {
                    value(
                        org.hamcrest.Matchers.containsInAnyOrder(
                            "client-confidential-symmetric",
                            "client-public",
                            "sso-openid-connect",
                            "permission-user",
                            "permission-v1",
                            "permission-v2",
                        ),
                    )
                }
                // no unsupported launch capabilities are advertised
                jsonPath("$.capabilities") {
                    value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItems("launch-ehr", "launch-standalone")))
                }
            }
    }

    @Test
    fun `the oauth endpoints are live`() {
        // The embedded authorization server owns /oauth/token now: a bare
        // request fails client authentication, not a 501.
        mockMvc.post("/oauth/token")
            .andExpect {
                status { isUnauthorized() }
            }

        // A bare authorize request is an OAuth protocol error (missing
        // client_id); the dev-login redirect for well-formed requests is
        // proven in AuthorizationCodeFlowIntegrationTest.
        mockMvc.get("/oauth/authorize") {
            accept = org.springframework.http.MediaType.TEXT_HTML
        }.andExpect {
            status { isBadRequest() }
        }
    }

    @Test
    fun `clinical routes remain protected`() {
        mockMvc.get("/api/v1/patients/${UUID.randomUUID()}")
            .andExpect {
                status { isUnauthorized() }
            }
        mockMvc.get("/fhir/r4/Patient/${UUID.randomUUID()}")
            .andExpect {
                status { isUnauthorized() }
            }
    }
}
