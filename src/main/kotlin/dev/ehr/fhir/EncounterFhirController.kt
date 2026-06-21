package dev.ehr.fhir

import dev.ehr.encounter.Encounter
import dev.ehr.encounter.EncounterId
import dev.ehr.encounter.EncounterService
import dev.ehr.terminology.CodeableConcept
import dev.ehr.terminology.CodeableConceptRepository
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

@RestController
@RequestMapping("/fhir/r4")
class EncounterFhirController(
    private val encounterService: EncounterService,
    private val encounterFhirMapper: EncounterFhirMapper,
    private val codeableConceptRepository: CodeableConceptRepository,
    private val responses: FhirResponseFactory,
    private val requestSupport: FhirRequestSupport,
) {
    @GetMapping("/Encounter/{id}", produces = [FHIR_JSON])
    fun read(
        authentication: Authentication,
        @PathVariable id: String,
    ): ResponseEntity<String> {
        val principal = requestSupport.securityPrincipal(authentication)
        val encounterId = requestSupport.parseUuid(id)?.let(::EncounterId)
            ?: return responses.operationOutcome(
                HttpStatus.NOT_FOUND,
                OperationOutcome.IssueType.NOTFOUND,
                "Encounter not found",
            )

        return try {
            val encounter = encounterService.get(principal, encounterId)
            responses.resource(
                HttpStatus.OK,
                encounterFhirMapper.toFhirEncounter(encounter, classConcept(encounter)),
            )
        } catch (exception: ResponseStatusException) {
            responses.fromStatusException(exception)
        }
    }

    @GetMapping("/Encounter", produces = [FHIR_JSON])
    fun search(
        authentication: Authentication,
        @RequestParam patient: String?,
    ): ResponseEntity<String> {
        val principal = requestSupport.securityPrincipal(authentication)
        val patientId = requestSupport.parsePatientParam(patient)
            ?: return responses.operationOutcome(
                HttpStatus.BAD_REQUEST,
                OperationOutcome.IssueType.INVALID,
                "The patient search parameter is required as a logical id or Patient/{id} reference",
            )

        return try {
            val encounters = encounterService.timeline(principal, patientId)
            val bundle = Bundle()
            bundle.type = Bundle.BundleType.SEARCHSET
            bundle.total = encounters.size
            bundle.addLink(
                Bundle.BundleLinkComponent()
                    .setRelation("self")
                    .setUrl(ServletUriComponentsBuilder.fromCurrentRequest().build().toUriString()),
            )
            encounters.forEach { encounter ->
                val fhirEncounter = encounterFhirMapper.toFhirEncounter(encounter, classConcept(encounter))
                bundle.addEntry(
                    Bundle.BundleEntryComponent()
                        .setFullUrl(requestSupport.resourceFullUrl("Encounter", fhirEncounter.idElement.idPart))
                        .setResource(fhirEncounter)
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

    private fun classConcept(encounter: Encounter): CodeableConcept =
        codeableConceptRepository.findById(encounter.classConceptId)
            ?: throw IllegalStateException("encounter class concept is missing")
}
