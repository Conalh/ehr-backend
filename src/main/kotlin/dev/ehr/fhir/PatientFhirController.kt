package dev.ehr.fhir

import ca.uhn.fhir.context.FhirContext
import dev.ehr.patient.PatientId
import dev.ehr.patient.PatientService
import dev.ehr.security.SecurityPrincipal
import org.hl7.fhir.r4.model.Bundle
import org.hl7.fhir.r4.model.OperationOutcome
import org.hl7.fhir.r4.model.Resource
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.servlet.support.ServletUriComponentsBuilder
import java.util.UUID

const val FHIR_JSON = "application/fhir+json"

@RestController
@RequestMapping("/fhir/r4")
class PatientFhirController(
    private val patientService: PatientService,
    private val patientFhirMapper: PatientFhirMapper,
    private val fhirContext: FhirContext,
) {
    @GetMapping("/Patient/{id}", produces = [FHIR_JSON])
    fun read(
        authentication: Authentication,
        @PathVariable id: String,
    ): ResponseEntity<String> {
        val principal = securityPrincipal(authentication)
        val patientId = parsePatientId(id)
            ?: return operationOutcome(HttpStatus.NOT_FOUND, OperationOutcome.IssueType.NOTFOUND, "Patient not found")

        return try {
            val result = patientService.get(principal, patientId)
            fhirResponse(HttpStatus.OK, patientFhirMapper.toFhirPatient(result))
        } catch (exception: ResponseStatusException) {
            toOperationOutcomeResponse(exception)
        }
    }

    @GetMapping("/Patient", produces = [FHIR_JSON])
    fun search(
        authentication: Authentication,
        @RequestParam identifier: String?,
    ): ResponseEntity<String> {
        val principal = securityPrincipal(authentication)
        val token = parseIdentifierToken(identifier)
            ?: return operationOutcome(
                HttpStatus.BAD_REQUEST,
                OperationOutcome.IssueType.INVALID,
                "The identifier search parameter is required in system|value form",
            )

        return try {
            val results = patientService.searchByIdentifier(principal, token.first, token.second)
            val bundle = Bundle()
            bundle.type = Bundle.BundleType.SEARCHSET
            bundle.total = results.size
            results.forEach { result ->
                val fhirPatient = patientFhirMapper.toFhirPatient(result)
                bundle.addEntry(
                    Bundle.BundleEntryComponent()
                        .setFullUrl(patientFullUrl(fhirPatient.idElement.idPart))
                        .setResource(fhirPatient)
                        .setSearch(
                            Bundle.BundleEntrySearchComponent()
                                .setMode(Bundle.SearchEntryMode.MATCH),
                        ),
                )
            }
            fhirResponse(HttpStatus.OK, bundle)
        } catch (exception: ResponseStatusException) {
            toOperationOutcomeResponse(exception)
        }
    }

    private fun securityPrincipal(authentication: Authentication): SecurityPrincipal =
        authentication.principal as? SecurityPrincipal
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Security principal is not available")

    private fun parsePatientId(id: String): PatientId? =
        runCatching { PatientId(UUID.fromString(id)) }.getOrNull()

    private fun parseIdentifierToken(identifier: String?): Pair<String, String>? {
        if (identifier == null) {
            return null
        }
        val separatorIndex = identifier.indexOf('|')
        if (separatorIndex <= 0 || separatorIndex == identifier.length - 1) {
            return null
        }
        val system = identifier.substring(0, separatorIndex)
        val value = identifier.substring(separatorIndex + 1)
        if (system.isBlank() || value.isBlank()) {
            return null
        }
        return system to value
    }

    private fun patientFullUrl(idPart: String): String =
        ServletUriComponentsBuilder.fromCurrentContextPath()
            .path("/fhir/r4/Patient/{id}")
            .buildAndExpand(idPart)
            .toUriString()

    private fun toOperationOutcomeResponse(exception: ResponseStatusException): ResponseEntity<String> {
        val status = HttpStatus.valueOf(exception.statusCode.value())
        val issueType = when (status) {
            HttpStatus.FORBIDDEN -> OperationOutcome.IssueType.FORBIDDEN
            HttpStatus.NOT_FOUND -> OperationOutcome.IssueType.NOTFOUND
            HttpStatus.BAD_REQUEST -> OperationOutcome.IssueType.INVALID
            else -> OperationOutcome.IssueType.PROCESSING
        }
        return operationOutcome(status, issueType, exception.reason ?: "Request could not be processed")
    }

    private fun operationOutcome(
        status: HttpStatus,
        issueType: OperationOutcome.IssueType,
        diagnostics: String,
    ): ResponseEntity<String> {
        val outcome = OperationOutcome()
        outcome.addIssue(
            OperationOutcome.OperationOutcomeIssueComponent()
                .setSeverity(OperationOutcome.IssueSeverity.ERROR)
                .setCode(issueType)
                .setDiagnostics(diagnostics),
        )
        return fhirResponse(status, outcome)
    }

    private fun fhirResponse(
        status: HttpStatus,
        resource: Resource,
    ): ResponseEntity<String> =
        ResponseEntity.status(status)
            .contentType(MediaType.parseMediaType(FHIR_JSON))
            .body(fhirContext.newJsonParser().encodeResourceToString(resource))
}
