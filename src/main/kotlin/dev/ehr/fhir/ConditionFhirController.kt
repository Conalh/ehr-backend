package dev.ehr.fhir

import dev.ehr.condition.Condition
import dev.ehr.condition.ConditionId
import dev.ehr.condition.ConditionService
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
class ConditionFhirController(
    private val conditionService: ConditionService,
    private val conditionFhirMapper: ConditionFhirMapper,
    private val codeableConceptRepository: CodeableConceptRepository,
    private val provenanceQueryService: dev.ehr.provenance.ProvenanceQueryService,
    private val provenanceFhirMapper: ProvenanceFhirMapper,
    private val responses: FhirResponseFactory,
) {
    @GetMapping("/Condition/{id}", produces = [FHIR_JSON])
    fun read(
        authentication: Authentication,
        @PathVariable id: String,
    ): ResponseEntity<String> {
        val principal = securityPrincipal(authentication)
        val conditionId = parseUuid(id)?.let(::ConditionId)
            ?: return responses.operationOutcome(
                HttpStatus.NOT_FOUND,
                OperationOutcome.IssueType.NOTFOUND,
                "Condition not found",
            )

        return try {
            val condition = conditionService.get(principal, conditionId)
            responses.resource(
                HttpStatus.OK,
                conditionFhirMapper.toFhirCondition(condition, codeConcept(condition)),
            )
        } catch (exception: ResponseStatusException) {
            responses.fromStatusException(exception)
        }
    }

    @GetMapping("/Condition", produces = [FHIR_JSON])
    fun search(
        authentication: Authentication,
        @RequestParam patient: String?,
        @RequestParam(name = "category") category: String?,
        @RequestParam(name = "clinical-status") clinicalStatus: String?,
        @RequestParam(name = "_revinclude") revInclude: String?,
    ): ResponseEntity<String> {
        val principal = securityPrincipal(authentication)
        val patientId = parsePatientParam(patient)
            ?: return responses.operationOutcome(
                HttpStatus.BAD_REQUEST,
                OperationOutcome.IssueType.INVALID,
                "The patient search parameter is required as a logical id or Patient/{id} reference",
            )

        return try {
            // Every condition in this model is a problem-list item: a
            // matching category returns everything, any other an honest
            // empty bundle.
            val categoryMatches = category?.let(FhirTokenParam::parse)
                ?.matches(ConditionFhirMapper.US_CORE_CONDITION_CATEGORY_SYSTEM, "problem-list-item")
                ?: true
            val statusToken = clinicalStatus?.let(FhirTokenParam::parse)
            val conditions = conditionService.problemList(principal, patientId)
                .filter { condition ->
                    categoryMatches &&
                        (
                            statusToken == null ||
                                statusToken.matches(
                                    dev.ehr.terminology.CanonicalCodeSystems.HL7_CONDITION_CLINICAL,
                                    condition.clinicalStatus.dbValue,
                                )
                            )
                }
            val bundle = Bundle()
            bundle.type = Bundle.BundleType.SEARCHSET
            bundle.total = conditions.size
            bundle.addLink(
                Bundle.BundleLinkComponent()
                    .setRelation("self")
                    .setUrl(ServletUriComponentsBuilder.fromCurrentRequest().build().toUriString()),
            )
            conditions.forEach { condition ->
                val fhirCondition = conditionFhirMapper.toFhirCondition(condition, codeConcept(condition))
                bundle.addEntry(
                    Bundle.BundleEntryComponent()
                        .setFullUrl(conditionFullUrl(fhirCondition.idElement.idPart))
                        .setResource(fhirCondition)
                        .setSearch(
                            Bundle.BundleEntrySearchComponent()
                                .setMode(Bundle.SearchEntryMode.MATCH),
                        ),
                )
            }
            if (ProvenanceRevInclude.isRequested(revInclude) && conditions.isNotEmpty()) {
                provenanceQueryService.searchByTargets(
                    principal,
                    patientId.value,
                    "CONDITION",
                    conditions.map { it.id.value },
                ).forEach { event ->
                    bundle.addEntry(
                        Bundle.BundleEntryComponent()
                            .setFullUrl(provenanceFullUrl(event.id))
                            .setResource(provenanceFhirMapper.toFhirProvenance(event))
                            .setSearch(
                                Bundle.BundleEntrySearchComponent()
                                    .setMode(Bundle.SearchEntryMode.INCLUDE),
                            ),
                    )
                }
            }
            responses.resource(HttpStatus.OK, bundle)
        } catch (exception: ResponseStatusException) {
            responses.fromStatusException(exception)
        }
    }

    private fun codeConcept(condition: Condition): CodeableConcept =
        codeableConceptRepository.findById(condition.codeConceptId)
            ?: throw IllegalStateException("condition code concept is missing")

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

    private fun conditionFullUrl(idPart: String): String =
        ServletUriComponentsBuilder.fromCurrentContextPath()
            .path("/fhir/r4/Condition/{id}")
            .buildAndExpand(idPart)
            .toUriString()

    private fun provenanceFullUrl(id: UUID): String =
        ServletUriComponentsBuilder.fromCurrentContextPath()
            .path("/fhir/r4/Provenance/{id}")
            .buildAndExpand(id)
            .toUriString()
}
