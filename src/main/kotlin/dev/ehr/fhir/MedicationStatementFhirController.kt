package dev.ehr.fhir

import dev.ehr.medication.MedicationStatement
import dev.ehr.medication.MedicationStatementId
import dev.ehr.medication.MedicationStatementService
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
class MedicationStatementFhirController(
    private val medicationStatementService: MedicationStatementService,
    private val medicationStatementFhirMapper: MedicationStatementFhirMapper,
    private val codeableConceptRepository: CodeableConceptRepository,
    private val responses: FhirResponseFactory,
) {
    @GetMapping("/MedicationStatement/{id}", produces = [FHIR_JSON])
    fun read(
        authentication: Authentication,
        @PathVariable id: String,
    ): ResponseEntity<String> {
        val principal = securityPrincipal(authentication)
        val medicationStatementId = parseUuid(id)?.let(::MedicationStatementId)
            ?: return responses.operationOutcome(
                HttpStatus.NOT_FOUND,
                OperationOutcome.IssueType.NOTFOUND,
                "MedicationStatement not found",
            )

        return try {
            val statement = medicationStatementService.get(principal, medicationStatementId)
            responses.resource(
                HttpStatus.OK,
                medicationStatementFhirMapper.toFhirMedicationStatement(statement, medicationConcept(statement)),
            )
        } catch (exception: ResponseStatusException) {
            responses.fromStatusException(exception)
        }
    }

    @GetMapping("/MedicationStatement", produces = [FHIR_JSON])
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
            val statements = medicationStatementService.listForPatient(principal, patientId)
            val bundle = Bundle()
            bundle.type = Bundle.BundleType.SEARCHSET
            bundle.total = statements.size
            statements.forEach { statement ->
                val fhirStatement = medicationStatementFhirMapper
                    .toFhirMedicationStatement(statement, medicationConcept(statement))
                bundle.addEntry(
                    Bundle.BundleEntryComponent()
                        .setFullUrl(medicationStatementFullUrl(fhirStatement.idElement.idPart))
                        .setResource(fhirStatement)
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

    private fun medicationConcept(statement: MedicationStatement): CodeableConcept =
        codeableConceptRepository.findById(statement.medicationConceptId)
            ?: throw IllegalStateException("medication concept is missing")

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

    private fun medicationStatementFullUrl(idPart: String): String =
        ServletUriComponentsBuilder.fromCurrentContextPath()
            .path("/fhir/r4/MedicationStatement/{id}")
            .buildAndExpand(idPart)
            .toUriString()
}
