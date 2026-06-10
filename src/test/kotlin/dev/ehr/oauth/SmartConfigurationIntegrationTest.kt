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
                jsonPath("$.grant_types_supported[0]") { value("client_credentials") }
                jsonPath("$.scopes_supported") { value(org.hamcrest.Matchers.hasItems("user/*.read", "system/*.cruds", "openid")) }
                jsonPath("$.capabilities") {
                    value(
                        org.hamcrest.Matchers.containsInAnyOrder(
                            "client-confidential-symmetric",
                            "permission-user",
                            "permission-v1",
                            "permission-v2",
                        ),
                    )
                }
                // no unsupported launch/openid capabilities are advertised
                jsonPath("$.capabilities") {
                    value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItems("launch-ehr", "sso-openid-connect")))
                }
            }
    }

    @Test
    fun `oauth stubs refuse loudly and are public`() {
        mockMvc.post("/oauth/token")
            .andExpect {
                status { isNotImplemented() }
                jsonPath("$.error") { value("unsupported_grant_type") }
                jsonPath("$.error_description") { exists() }
            }

        mockMvc.get("/oauth/authorize")
            .andExpect {
                status { isNotImplemented() }
                jsonPath("$.error") { value("unsupported") }
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
