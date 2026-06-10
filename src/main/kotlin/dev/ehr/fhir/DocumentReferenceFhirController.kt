package dev.ehr.fhir

import dev.ehr.note.ClinicalNote
import dev.ehr.note.ClinicalNoteId
import dev.ehr.note.ClinicalNoteService
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
class DocumentReferenceFhirController(
    private val clinicalNoteService: ClinicalNoteService,
    private val documentReferenceFhirMapper: DocumentReferenceFhirMapper,
    private val codeableConceptRepository: CodeableConceptRepository,
    private val responses: FhirResponseFactory,
) {
    @GetMapping("/DocumentReference/{id}", produces = [FHIR_JSON])
    fun read(
        authentication: Authentication,
        @PathVariable id: String,
    ): ResponseEntity<String> {
        val principal = securityPrincipal(authentication)
        val noteId = parseUuid(id)?.let(::ClinicalNoteId)
            ?: return responses.operationOutcome(
                HttpStatus.NOT_FOUND,
                OperationOutcome.IssueType.NOTFOUND,
                "DocumentReference not found",
            )

        return try {
            val note = clinicalNoteService.get(principal, noteId)
            responses.resource(
                HttpStatus.OK,
                documentReferenceFhirMapper.toFhirDocumentReference(note, typeConcept(note)),
            )
        } catch (exception: ResponseStatusException) {
            responses.fromStatusException(exception)
        }
    }

    @GetMapping("/DocumentReference", produces = [FHIR_JSON])
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
            val notes = clinicalNoteService.listForPatient(principal, patientId)
            val bundle = Bundle()
            bundle.type = Bundle.BundleType.SEARCHSET
            bundle.total = notes.size
            bundle.addLink(
                Bundle.BundleLinkComponent()
                    .setRelation("self")
                    .setUrl(ServletUriComponentsBuilder.fromCurrentRequest().build().toUriString()),
            )
            notes.forEach { note ->
                val fhirDocument = documentReferenceFhirMapper.toFhirDocumentReference(note, typeConcept(note))
                bundle.addEntry(
                    Bundle.BundleEntryComponent()
                        .setFullUrl(documentReferenceFullUrl(fhirDocument.idElement.idPart))
                        .setResource(fhirDocument)
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

    private fun typeConcept(note: ClinicalNote): CodeableConcept =
        codeableConceptRepository.findById(note.typeConceptId)
            ?: throw IllegalStateException("note type concept is missing")

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

    private fun documentReferenceFullUrl(idPart: String): String =
        ServletUriComponentsBuilder.fromCurrentContextPath()
            .path("/fhir/r4/DocumentReference/{id}")
            .buildAndExpand(idPart)
            .toUriString()
}
