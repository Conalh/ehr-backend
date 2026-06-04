package dev.ehr.runtime

import org.hamcrest.Matchers.matchesPattern
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

@WebMvcTest(controllers = [HealthController::class])
@Import(CorrelationIdFilter::class)
class CorrelationIdFilterTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Test
    fun `generates a correlation id when request has none`() {
        mockMvc.get("/internal/health")
            .andExpect {
                status { isOk() }
                header {
                    string(
                        "X-Correlation-Id",
                        matchesPattern("[0-9a-fA-F-]{36}"),
                    )
                }
            }
    }

    @Test
    fun `echoes an accepted inbound correlation id`() {
        mockMvc.get("/internal/health") {
            header("X-Correlation-Id", "req-12345")
        }.andExpect {
            status { isOk() }
            header { string("X-Correlation-Id", "req-12345") }
        }
    }
}
