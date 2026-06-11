package dev.ehr.fhir

import dev.ehr.identity.PractitionerId
import dev.ehr.practitioner.PractitionerService
import dev.ehr.security.SecurityPrincipal
import org.hl7.fhir.r4.model.OperationOutcome
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

@RestController
@RequestMapping("/fhir/r4")
class PractitionerFhirController(
    private val practitionerService: PractitionerService,
    private val practitionerFhirMapper: PractitionerFhirMapper,
    private val responses: FhirResponseFactory,
) {
    @GetMapping("/Practitioner/{id}", produces = [FHIR_JSON])
    fun read(
        authentication: Authentication,
        @PathVariable id: String,
    ): ResponseEntity<String> {
        val principal = securityPrincipal(authentication)
        val practitionerId = parseUuid(id)?.let(::PractitionerId)
            ?: return responses.operationOutcome(
                HttpStatus.NOT_FOUND,
                OperationOutcome.IssueType.NOTFOUND,
                "Practitioner not found",
            )

        return try {
            val practitioner = practitionerService.get(principal, practitionerId)
            responses.resource(HttpStatus.OK, practitionerFhirMapper.toFhirPractitioner(practitioner))
        } catch (exception: ResponseStatusException) {
            responses.fromStatusException(exception)
        }
    }

    private fun securityPrincipal(authentication: Authentication): SecurityPrincipal =
        authentication.principal as? SecurityPrincipal
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Security principal is not available")

    private fun parseUuid(value: String): UUID? =
        runCatching { UUID.fromString(value) }.getOrNull()
}
