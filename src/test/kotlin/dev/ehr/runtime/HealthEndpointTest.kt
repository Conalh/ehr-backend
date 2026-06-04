package dev.ehr.runtime

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

@WebMvcTest(controllers = [HealthController::class])
class HealthEndpointTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Test
    fun `internal health returns service status`() {
        mockMvc.get("/internal/health")
            .andExpect {
                status { isOk() }
                jsonPath("$.status") { value("UP") }
                jsonPath("$.service") { value("ehr-core") }
            }
    }
}
