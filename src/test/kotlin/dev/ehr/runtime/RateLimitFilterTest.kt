package dev.ehr.runtime

import jakarta.servlet.FilterChain
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

class RateLimitFilterTest {
    private var nowMillis: Long = 0L

    private fun filter(limit: Int): RateLimitFilter =
        RateLimitFilter(
            properties = EhrProperties(
                security = EhrProperties.Security("0123456789012345678901234567890123456789"),
                rateLimit = EhrProperties.RateLimit(requestsPerMinute = limit),
            ),
            clock = { nowMillis },
        )

    private fun request(
        path: String,
        address: String = "10.0.0.1",
    ): MockHttpServletRequest =
        MockHttpServletRequest("GET", path).apply {
            requestURI = path
            remoteAddr = address
        }

    private fun run(
        filter: RateLimitFilter,
        path: String,
        address: String = "10.0.0.1",
    ): MockHttpServletResponse {
        val response = MockHttpServletResponse()
        val chain: FilterChain = MockFilterChain()
        filter.doFilter(request(path, address), response, chain)
        return response
    }

    @Test
    fun `requests beyond the limit are rejected with retry after`() {
        val filter = filter(limit = 3)

        repeat(3) {
            assertEquals(200, run(filter, "/api/v1/patients").status)
        }
        val rejected = run(filter, "/api/v1/patients")
        assertEquals(429, rejected.status)
        assertNotNull(rejected.getHeader("Retry-After"))
        assertEquals("""{"error":"rate_limited","retryAfterSeconds":60}""", rejected.contentAsString)
    }

    @Test
    fun `fhir paths are limited but other paths are not`() {
        val filter = filter(limit = 1)

        assertEquals(200, run(filter, "/fhir/r4/Patient/x").status)
        assertEquals(429, run(filter, "/fhir/r4/Patient/x").status)
        // discovery and health are never throttled
        repeat(5) {
            assertEquals(200, run(filter, "/.well-known/smart-configuration").status)
            assertEquals(200, run(filter, "/actuator/health").status)
        }
    }

    @Test
    fun `clients are limited independently`() {
        val filter = filter(limit = 1)

        assertEquals(200, run(filter, "/api/v1/patients", "10.0.0.1").status)
        assertEquals(429, run(filter, "/api/v1/patients", "10.0.0.1").status)
        assertEquals(200, run(filter, "/api/v1/patients", "10.0.0.2").status)
    }

    @Test
    fun `the window resets after a minute`() {
        val filter = filter(limit = 1)

        assertEquals(200, run(filter, "/api/v1/patients").status)
        assertEquals(429, run(filter, "/api/v1/patients").status)

        nowMillis += 60_001
        assertEquals(200, run(filter, "/api/v1/patients").status)
    }

    @Test
    fun `oauth token and login paths are rate limited`() {
        val filter = filter(limit = 1)

        assertEquals(200, run(filter, "/oauth/token", "10.0.0.10").status)
        assertEquals(429, run(filter, "/oauth/token", "10.0.0.10").status)
        assertEquals(200, run(filter, "/login", "10.0.0.11").status)
        assertEquals(429, run(filter, "/login", "10.0.0.11").status)
    }
}
