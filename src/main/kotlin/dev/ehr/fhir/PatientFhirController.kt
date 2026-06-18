package dev.ehr.fhir

import dev.ehr.patient.PatientId
import dev.ehr.patient.PatientService
import dev.ehr.security.SecurityPrincipal
import org.hl7.fhir.r4.model.Bundle
import org.hl7.fhir.r4.model.OperationOutcome
import org.springframework.http.HttpStatus
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

@RestController
@RequestMapping("/fhir/r4")
class PatientFhirController(
    private val patientService: PatientService,
    private val patientFhirMapper: PatientFhirMapper,
    private val responses: FhirResponseFactory,
) {
    @GetMapping("/Patient/{id}", produces = [FHIR_JSON])
    fun read(
        authentication: Authentication,
        @PathVariable id: String,
    ): ResponseEntity<String> {
        val principal = securityPrincipal(authentication)
        val patientId = parsePatientId(id)
            ?: return responses.operationOutcome(
                HttpStatus.NOT_FOUND,
                OperationOutcome.IssueType.NOTFOUND,
                "Patient not found",
            )

        return try {
            val result = patientService.get(principal, patientId)
            responses.resource(HttpStatus.OK, patientFhirMapper.toFhirPatient(result))
        } catch (exception: ResponseStatusException) {
            responses.fromStatusException(exception)
        }
    }

    @GetMapping("/Patient", produces = [FHIR_JSON])
    fun search(
        authentication: Authentication,
        @RequestParam identifier: String?,
        @RequestParam(name = "_id") id: String?,
    ): ResponseEntity<String> {
        val principal = securityPrincipal(authentication)

        return try {
            val patientId = id?.let(::parsePatientId)
            val identifierToken = if (identifier != null) {
                parseIdentifierToken(identifier)
                    ?: return responses.operationOutcome(
                        HttpStatus.BAD_REQUEST,
                        OperationOutcome.IssueType.INVALID,
                        "The identifier search parameter must be in system|value form",
                    )
            } else {
                null
            }

            if (id == null && identifierToken == null) {
                return responses.operationOutcome(
                    HttpStatus.BAD_REQUEST,
                    OperationOutcome.IssueType.INVALID,
                    "The identifier or _id search parameter is required",
                )
            }

            if (id != null && patientId == null) {
                return responses.resource(HttpStatus.OK, searchBundle(emptyList()))
            }

            val results = patientService.search(
                principal = principal,
                patientId = patientId,
                identifierSystem = identifierToken?.first,
                identifierValue = identifierToken?.second,
            )
            responses.resource(HttpStatus.OK, searchBundle(results))
        } catch (exception: ResponseStatusException) {
            responses.fromStatusException(exception)
        }
    }

    private fun searchBundle(results: List<dev.ehr.patient.PatientWithIdentifiers>): Bundle {
        val bundle = Bundle()
        bundle.type = Bundle.BundleType.SEARCHSET
        bundle.total = results.size
        bundle.addLink(
            Bundle.BundleLinkComponent()
                .setRelation("self")
                .setUrl(ServletUriComponentsBuilder.fromCurrentRequest().build().toUriString()),
        )
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
        return bundle
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
}
