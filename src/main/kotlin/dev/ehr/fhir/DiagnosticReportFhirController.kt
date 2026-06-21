package dev.ehr.fhir

import dev.ehr.diagnostics.DiagnosticReport
import dev.ehr.diagnostics.DiagnosticReportId
import dev.ehr.diagnostics.DiagnosticReportService
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
class DiagnosticReportFhirController(
    private val diagnosticReportService: DiagnosticReportService,
    private val diagnosticReportFhirMapper: DiagnosticReportFhirMapper,
    private val codeableConceptRepository: CodeableConceptRepository,
    private val provenanceQueryService: dev.ehr.provenance.ProvenanceQueryService,
    private val provenanceFhirMapper: ProvenanceFhirMapper,
    private val responses: FhirResponseFactory,
    private val requestSupport: FhirRequestSupport,
) {
    @GetMapping("/DiagnosticReport/{id}", produces = [FHIR_JSON])
    fun read(
        authentication: Authentication,
        @PathVariable id: String,
    ): ResponseEntity<String> {
        val principal = requestSupport.securityPrincipal(authentication)
        val reportId = requestSupport.parseUuid(id)?.let(::DiagnosticReportId)
            ?: return responses.operationOutcome(
                HttpStatus.NOT_FOUND,
                OperationOutcome.IssueType.NOTFOUND,
                "DiagnosticReport not found",
            )

        return try {
            val report = diagnosticReportService.get(principal, reportId)
            responses.resource(
                HttpStatus.OK,
                diagnosticReportFhirMapper.toFhirDiagnosticReport(report, codeConcept(report)),
            )
        } catch (exception: ResponseStatusException) {
            responses.fromStatusException(exception)
        }
    }

    @GetMapping("/DiagnosticReport", produces = [FHIR_JSON])
    fun search(
        authentication: Authentication,
        @RequestParam patient: String?,
        @RequestParam(name = "category") category: String?,
        @RequestParam(name = "code") code: String?,
        @RequestParam(name = "date") date: List<String>?,
        @RequestParam(name = "_revinclude") revInclude: String?,
    ): ResponseEntity<String> {
        val principal = requestSupport.securityPrincipal(authentication)
        val patientId = requestSupport.parsePatientParam(patient)
            ?: return responses.operationOutcome(
                HttpStatus.BAD_REQUEST,
                OperationOutcome.IssueType.INVALID,
                "The patient search parameter is required as a logical id or Patient/{id} reference",
            )

        return try {
            // Every report in this model is a LAB report: a matching category
            // returns everything, any other returns an honest empty bundle.
            val categoryMatches = category?.let(FhirTokenParam::parse)
                ?.matches(DiagnosticReportFhirMapper.V2_0074_SYSTEM, "LAB")
                ?: true
            val codeToken = code?.let(FhirTokenParam::parse)
            val dateRanges = date.orEmpty().map(FhirDateRange::parse)
            val reports = diagnosticReportService.listForPatient(principal, patientId)
                .filter { report ->
                    categoryMatches &&
                        (codeToken == null || codeToken.matchesConcept(codeConcept(report))) &&
                        dateRanges.all { it.contains(report.issuedAt) }
                }
            val bundle = Bundle()
            bundle.type = Bundle.BundleType.SEARCHSET
            bundle.total = reports.size
            bundle.addLink(
                Bundle.BundleLinkComponent()
                    .setRelation("self")
                    .setUrl(ServletUriComponentsBuilder.fromCurrentRequest().build().toUriString()),
            )
            reports.forEach { report ->
                val fhirReport = diagnosticReportFhirMapper.toFhirDiagnosticReport(report, codeConcept(report))
                bundle.addEntry(
                    Bundle.BundleEntryComponent()
                        .setFullUrl(requestSupport.resourceFullUrl("DiagnosticReport", fhirReport.idElement.idPart))
                        .setResource(fhirReport)
                        .setSearch(
                            Bundle.BundleEntrySearchComponent()
                                .setMode(Bundle.SearchEntryMode.MATCH),
                        ),
                )
            }
            if (ProvenanceRevInclude.isRequested(revInclude) && reports.isNotEmpty()) {
                provenanceQueryService.searchByTargets(
                    principal,
                    patientId.value,
                    "DIAGNOSTIC_REPORT",
                    reports.map { it.id.value },
                ).forEach { event ->
                    bundle.addEntry(
                        Bundle.BundleEntryComponent()
                            .setFullUrl(requestSupport.resourceFullUrl("Provenance", event.id.toString()))
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

    private fun codeConcept(report: DiagnosticReport): CodeableConcept =
        codeableConceptRepository.findById(report.codeConceptId)
            ?: throw IllegalStateException("diagnostic report code concept is missing")
}
