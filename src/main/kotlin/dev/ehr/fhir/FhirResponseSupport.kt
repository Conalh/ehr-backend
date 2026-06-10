package dev.ehr.fhir

import ca.uhn.fhir.context.FhirContext
import org.hl7.fhir.r4.model.OperationOutcome
import org.hl7.fhir.r4.model.Resource
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component
import org.springframework.web.server.ResponseStatusException

const val FHIR_JSON = "application/fhir+json"

@Component
class FhirResponseFactory(
    private val fhirContext: FhirContext,
) {
    fun resource(
        status: HttpStatus,
        resource: Resource,
    ): ResponseEntity<String> =
        ResponseEntity.status(status)
            .contentType(MediaType.parseMediaType(FHIR_JSON))
            .body(fhirContext.newJsonParser().encodeResourceToString(resource))

    fun operationOutcome(
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
        return resource(status, outcome)
    }

    fun fromStatusException(exception: ResponseStatusException): ResponseEntity<String> {
        val status = HttpStatus.valueOf(exception.statusCode.value())
        val issueType = when (status) {
            HttpStatus.FORBIDDEN -> OperationOutcome.IssueType.FORBIDDEN
            HttpStatus.NOT_FOUND -> OperationOutcome.IssueType.NOTFOUND
            HttpStatus.BAD_REQUEST -> OperationOutcome.IssueType.INVALID
            HttpStatus.CONFLICT -> OperationOutcome.IssueType.CONFLICT
            else -> OperationOutcome.IssueType.PROCESSING
        }
        return operationOutcome(status, issueType, exception.reason ?: "Request could not be processed")
    }
}
