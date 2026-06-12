package dev.ehr.fhir

import dev.ehr.observation.Observation
import dev.ehr.observation.ObservationCategory
import dev.ehr.observation.ObservationId
import dev.ehr.observation.ObservationService
import dev.ehr.observation.ObservationValue
import dev.ehr.patient.PatientId
import dev.ehr.security.SecurityPrincipal
import dev.ehr.terminology.CodeableConcept
import dev.ehr.terminology.CodeableConceptId
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
import java.util.UUID

@RestController
@RequestMapping("/fhir/r4")
class ObservationFhirController(
    private val observationService: ObservationService,
    private val observationFhirMapper: ObservationFhirMapper,
    private val codeableConceptRepository: CodeableConceptRepository,
    private val responses: FhirResponseFactory,
) {
    @GetMapping("/Observation/{id}", produces = [FHIR_JSON])
    fun read(
        authentication: Authentication,
        @PathVariable id: String,
    ): ResponseEntity<String> {
        val principal = securityPrincipal(authentication)
        val observationId = parseUuid(id)?.let(::ObservationId)
            ?: return responses.operationOutcome(
                HttpStatus.NOT_FOUND,
                OperationOutcome.IssueType.NOTFOUND,
                "Observation not found",
            )

        return try {
            val observation = observationService.get(principal, observationId)
            responses.resource(HttpStatus.OK, toFhir(observation))
        } catch (exception: ResponseStatusException) {
            responses.fromStatusException(exception)
        }
    }

    @GetMapping("/Observation", produces = [FHIR_JSON])
    fun search(
        authentication: Authentication,
        @RequestParam patient: String?,
        @RequestParam category: String?,
        @RequestParam(name = "code") code: String?,
        @RequestParam(name = "date") date: List<String>?,
    ): ResponseEntity<String> {
        val principal = securityPrincipal(authentication)
        val patientId = parsePatientParam(patient)
            ?: return responses.operationOutcome(
                HttpStatus.BAD_REQUEST,
                OperationOutcome.IssueType.INVALID,
                "The patient search parameter is required as a logical id or Patient/{id} reference",
            )
        val categoryFilter = category?.let { raw ->
            runCatching { ObservationCategory.fromDb(raw) }.getOrNull()
                ?: return responses.operationOutcome(
                    HttpStatus.BAD_REQUEST,
                    OperationOutcome.IssueType.INVALID,
                    "The category search parameter is not a supported observation category",
                )
        }

        return try {
            val codeToken = code?.let(FhirTokenParam::parse)
            val dateRanges = date.orEmpty().map(FhirDateRange::parse)
            val observations = observationService.listForPatient(principal, patientId, categoryFilter)
                .filter { observation ->
                    (codeToken == null || codeToken.matchesConcept(concept(observation.codeConceptId.value, "observation code"))) &&
                        dateRanges.all { it.contains(observation.effectiveAt) }
                }
            val bundle = Bundle()
            bundle.type = Bundle.BundleType.SEARCHSET
            bundle.total = observations.size
            bundle.addLink(
                Bundle.BundleLinkComponent()
                    .setRelation("self")
                    .setUrl(ServletUriComponentsBuilder.fromCurrentRequest().build().toUriString()),
            )
            observations.forEach { observation ->
                val fhirObservation = toFhir(observation)
                bundle.addEntry(
                    Bundle.BundleEntryComponent()
                        .setFullUrl(observationFullUrl(fhirObservation.idElement.idPart))
                        .setResource(fhirObservation)
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

    private fun toFhir(observation: Observation) =
        observationFhirMapper.toFhirObservation(
            observation = observation,
            codeConcept = concept(observation.codeConceptId.value, "observation code"),
            valueConcept = (observation.value as? ObservationValue.Coded)
                ?.let { concept(it.conceptId.value, "observation value") },
        )

    private fun concept(
        conceptId: UUID,
        kind: String,
    ): CodeableConcept =
        codeableConceptRepository.findById(CodeableConceptId(conceptId))
            ?: throw IllegalStateException("$kind concept is missing")

    private fun securityPrincipal(authentication: Authentication): SecurityPrincipal =
        authentication.principal as? SecurityPrincipal
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Security principal is not available")

    private fun parsePatientParam(patient: String?): PatientId? {
        if (patient.isNullOrBlank()) {
            return null
        }
        return parseUuid(patient.removePrefix("Patient/"))?.let(::PatientId)
    }

    private fun parseUuid(value: String): UUID? =
        runCatching { UUID.fromString(value) }.getOrNull()

    private fun observationFullUrl(idPart: String): String =
        ServletUriComponentsBuilder.fromCurrentContextPath()
            .path("/fhir/r4/Observation/{id}")
            .buildAndExpand(idPart)
            .toUriString()
}
