package dev.ehr.fhir

import dev.ehr.allergy.Allergy
import dev.ehr.allergy.AllergyId
import dev.ehr.allergy.AllergyService
import dev.ehr.patient.PatientId
import dev.ehr.security.SecurityPrincipal
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
import java.util.UUID

@RestController
@RequestMapping("/fhir/r4")
class AllergyFhirController(
    private val allergyService: AllergyService,
    private val allergyFhirMapper: AllergyFhirMapper,
    private val codeableConceptRepository: CodeableConceptRepository,
    private val responses: FhirResponseFactory,
) {
    @GetMapping("/AllergyIntolerance/{id}", produces = [FHIR_JSON])
    fun read(
        authentication: Authentication,
        @PathVariable id: String,
    ): ResponseEntity<String> {
        val principal = securityPrincipal(authentication)
        val allergyId = parseUuid(id)?.let(::AllergyId)
            ?: return responses.operationOutcome(
                HttpStatus.NOT_FOUND,
                OperationOutcome.IssueType.NOTFOUND,
                "AllergyIntolerance not found",
            )

        return try {
            val allergy = allergyService.get(principal, allergyId)
            responses.resource(
                HttpStatus.OK,
                allergyFhirMapper.toFhirAllergyIntolerance(allergy, codeConcept(allergy)),
            )
        } catch (exception: ResponseStatusException) {
            responses.fromStatusException(exception)
        }
    }

    @GetMapping("/AllergyIntolerance", produces = [FHIR_JSON])
    fun search(
        authentication: Authentication,
        @RequestParam patient: String?,
    ): ResponseEntity<String> {
        val principal = securityPrincipal(authentication)
        val patientId = parsePatientParam(patient)
            ?: return responses.operationOutcome(
                HttpStatus.BAD_REQUEST,
                OperationOutcome.IssueType.INVALID,
                "The patient search parameter is required as a logical id or Patient/{id} reference",
            )

        return try {
            val allergies = allergyService.allergyList(principal, patientId)
            val bundle = Bundle()
            bundle.type = Bundle.BundleType.SEARCHSET
            bundle.total = allergies.size
            bundle.addLink(
                Bundle.BundleLinkComponent()
                    .setRelation("self")
                    .setUrl(ServletUriComponentsBuilder.fromCurrentRequest().build().toUriString()),
            )
            allergies.forEach { allergy ->
                val fhirAllergy = allergyFhirMapper.toFhirAllergyIntolerance(allergy, codeConcept(allergy))
                bundle.addEntry(
                    Bundle.BundleEntryComponent()
                        .setFullUrl(allergyFullUrl(fhirAllergy.idElement.idPart))
                        .setResource(fhirAllergy)
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

    private fun codeConcept(allergy: Allergy): CodeableConcept =
        codeableConceptRepository.findById(allergy.codeConceptId)
            ?: throw IllegalStateException("allergy code concept is missing")

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

    private fun allergyFullUrl(idPart: String): String =
        ServletUriComponentsBuilder.fromCurrentContextPath()
            .path("/fhir/r4/AllergyIntolerance/{id}")
            .buildAndExpand(idPart)
            .toUriString()
}
