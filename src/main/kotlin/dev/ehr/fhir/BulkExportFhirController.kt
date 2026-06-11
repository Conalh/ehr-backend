package dev.ehr.fhir

import com.fasterxml.jackson.annotation.JsonProperty
import dev.ehr.export.ExportJobStatus
import dev.ehr.export.ExportService
import dev.ehr.security.SecurityPrincipal
import org.hl7.fhir.r4.model.OperationOutcome
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.servlet.support.ServletUriComponentsBuilder
import java.time.Instant
import java.util.UUID

/**
 * FHIR Bulk Data export protocol over the Slice 8 export engine.
 * System-level $export only; Group/Patient-level exports and the _type
 * filter are recorded gaps (docs/conformance/inferno-g10.md), refused
 * loudly rather than silently absorbed.
 */
@RestController
@RequestMapping("/fhir/r4")
class BulkExportFhirController(
    private val exportService: ExportService,
    private val responses: FhirResponseFactory,
) {
    @GetMapping("/\$export")
    fun kickoff(
        authentication: Authentication,
        @RequestHeader(name = "Prefer", required = false) prefer: String?,
        @RequestParam(name = "_type", required = false) type: String?,
    ): ResponseEntity<String> {
        val principal = securityPrincipal(authentication)
        if (prefer?.contains("respond-async") != true) {
            return responses.operationOutcome(
                HttpStatus.BAD_REQUEST,
                OperationOutcome.IssueType.INVALID,
                "Bulk exports are asynchronous: the Prefer: respond-async header is required",
            )
        }
        if (type != null) {
            return responses.operationOutcome(
                HttpStatus.BAD_REQUEST,
                OperationOutcome.IssueType.NOTSUPPORTED,
                "The _type parameter is not supported; every supported resource type is exported",
            )
        }

        return try {
            val job = exportService.request(principal)
            ResponseEntity.accepted()
                .header("Content-Location", statusUrl(job.id))
                .build()
        } catch (exception: ResponseStatusException) {
            responses.fromStatusException(exception)
        }
    }

    @GetMapping("/\$export-status/{jobId}")
    fun status(
        authentication: Authentication,
        @PathVariable jobId: UUID,
    ): ResponseEntity<*> {
        val principal = securityPrincipal(authentication)
        return try {
            val (job, files) = exportService.status(principal, jobId)
            when (job.status) {
                ExportJobStatus.PENDING, ExportJobStatus.IN_PROGRESS ->
                    ResponseEntity.accepted()
                        .header("X-Progress", job.status.dbValue)
                        .build<Any>()
                ExportJobStatus.FAILED ->
                    responses.operationOutcome(
                        HttpStatus.INTERNAL_SERVER_ERROR,
                        OperationOutcome.IssueType.PROCESSING,
                        job.errorMessage ?: "The export failed",
                    )
                ExportJobStatus.COMPLETED ->
                    ResponseEntity.ok()
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(
                            BulkExportManifest(
                                transactionTime = job.requestedAt,
                                request = ServletUriComponentsBuilder.fromCurrentContextPath()
                                    .path("/fhir/r4/\$export")
                                    .toUriString(),
                                requiresAccessToken = true,
                                output = files.map { file ->
                                    BulkExportOutput(
                                        type = file.resourceType,
                                        url = ServletUriComponentsBuilder.fromCurrentContextPath()
                                            .path("/api/v1/export-jobs/{jobId}/files/{type}")
                                            .buildAndExpand(job.id, file.resourceType)
                                            .toUriString(),
                                        count = file.resourceCount,
                                    )
                                },
                                error = emptyList(),
                            ),
                        )
            }
        } catch (exception: ResponseStatusException) {
            responses.fromStatusException(exception)
        }
    }

    private fun statusUrl(jobId: UUID): String =
        ServletUriComponentsBuilder.fromCurrentContextPath()
            .path("/fhir/r4/\$export-status/{jobId}")
            .buildAndExpand(jobId)
            .toUriString()

    private fun securityPrincipal(authentication: Authentication): SecurityPrincipal =
        authentication.principal as? SecurityPrincipal
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Security principal is not available")
}

data class BulkExportOutput(
    val type: String,
    val url: String,
    val count: Int,
)

data class BulkExportManifest(
    val transactionTime: Instant,
    val request: String,
    @get:JsonProperty("requiresAccessToken")
    val requiresAccessToken: Boolean,
    val output: List<BulkExportOutput>,
    val error: List<BulkExportOutput>,
)
