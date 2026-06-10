package dev.ehr.fhir

import dev.ehr.provenance.ProvenanceQueryService
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
class ProvenanceFhirController(
    private val provenanceQueryService: ProvenanceQueryService,
    private val provenanceFhirMapper: ProvenanceFhirMapper,
    private val responses: FhirResponseFactory,
) {
    @GetMapping("/Provenance/{id}", produces = [FHIR_JSON])
    fun read(
        authentication: Authentication,
        @PathVariable id: String,
    ): ResponseEntity<String> {
        val principal = securityPrincipal(authentication)
        val provenanceId = parseUuid(id)
            ?: return responses.operationOutcome(
                HttpStatus.NOT_FOUND,
                OperationOutcome.IssueType.NOTFOUND,
                "Provenance not found",
            )

        return try {
            val event = provenanceQueryService.get(principal, provenanceId)
            responses.resource(HttpStatus.OK, provenanceFhirMapper.toFhirProvenance(event))
        } catch (exception: ResponseStatusException) {
            responses.fromStatusException(exception)
        }
    }

    @GetMapping("/Provenance", produces = [FHIR_JSON])
    fun search(
        authentication: Authentication,
        @RequestParam target: String?,
        @RequestParam patient: String?,
    ): ResponseEntity<String> {
        val principal = securityPrincipal(authentication)

        return try {
            val events = when {
                target != null -> {
                    val parsed = parseTarget(target)
                        ?: return responses.operationOutcome(
                            HttpStatus.BAD_REQUEST,
                            OperationOutcome.IssueType.INVALID,
                            "The target search parameter must be {SupportedType}/{id}",
                        )
                    provenanceQueryService.searchByTarget(principal, parsed.first, parsed.second)
                }
                patient != null -> {
                    val patientId = parseUuid(patient.removePrefix("Patient/"))
                        ?: return responses.operationOutcome(
                            HttpStatus.BAD_REQUEST,
                            OperationOutcome.IssueType.INVALID,
                            "The patient search parameter must be a logical id or Patient/{id} reference",
                        )
                    provenanceQueryService.searchByPatient(principal, patientId)
                }
                else -> return responses.operationOutcome(
                    HttpStatus.BAD_REQUEST,
                    OperationOutcome.IssueType.INVALID,
                    "Either the target or patient search parameter is required",
                )
            }

            val bundle = Bundle()
            bundle.type = Bundle.BundleType.SEARCHSET
            bundle.total = events.size
            events.forEach { event ->
                val fhirProvenance = provenanceFhirMapper.toFhirProvenance(event)
                bundle.addEntry(
                    Bundle.BundleEntryComponent()
                        .setFullUrl(provenanceFullUrl(fhirProvenance.idElement.idPart))
                        .setResource(fhirProvenance)
                        .setSearch(
                            Bundle.BundleEntrySearchComponent()
                                .setMode(Bundle.SearchEntryMode.MATCH),
                        ),
                )
            }
            responses.resource(HttpStatus.OK, bundle)
        } catch (exception: ResponseStatusException) {
            responses.fromStatusException(exception)
        }
    }

    private fun parseTarget(target: String): Pair<String, UUID>? {
        val separatorIndex = target.indexOf('/')
        if (separatorIndex <= 0 || separatorIndex == target.length - 1) {
            return null
        }
        val internalType = ProvenanceFhirMapper.internalTypeFor(target.substring(0, separatorIndex))
            ?: return null
        val targetId = parseUuid(target.substring(separatorIndex + 1)) ?: return null
        return internalType to targetId
    }

    private fun securityPrincipal(authentication: Authentication): SecurityPrincipal =
        authentication.principal as? SecurityPrincipal
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Security principal is not available")

    private fun parseUuid(value: String): UUID? =
        runCatching { UUID.fromString(value) }.getOrNull()

    private fun provenanceFullUrl(idPart: String): String =
        ServletUriComponentsBuilder.fromCurrentContextPath()
            .path("/fhir/r4/Provenance/{id}")
            .buildAndExpand(idPart)
            .toUriString()
}
