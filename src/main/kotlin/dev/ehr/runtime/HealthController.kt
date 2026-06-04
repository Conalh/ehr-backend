package dev.ehr.runtime

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/internal/health")
class HealthController {
    @GetMapping
    fun getHealth(): HealthResponse =
        HealthResponse(
            status = "UP",
            service = "ehr-core",
        )
}

data class HealthResponse(
    val status: String,
    val service: String,
)
