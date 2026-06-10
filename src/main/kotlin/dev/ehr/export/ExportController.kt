package dev.ehr.export

import dev.ehr.security.SecurityPrincipal
import org.springframework.core.io.FileSystemResource
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.servlet.support.ServletUriComponentsBuilder
import java.time.Instant
import java.util.UUID

const val FHIR_NDJSON = "application/fhir+ndjson"

@RestController
@RequestMapping("/api/v1/export-jobs")
class ExportController(
    private val exportService: ExportService,
) {
    @PostMapping
    fun request(authentication: Authentication): ResponseEntity<ExportJobResponse> {
        val job = exportService.request(securityPrincipal(authentication))
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(job.toResponse(emptyList()))
    }

    @GetMapping("/{jobId}")
    fun status(
        authentication: Authentication,
        @PathVariable jobId: UUID,
    ): ExportJobResponse {
        val (job, files) = exportService.status(securityPrincipal(authentication), jobId)
        return job.toResponse(files)
    }

    @GetMapping("/{jobId}/files/{resourceType}", produces = [FHIR_NDJSON])
    fun download(
        authentication: Authentication,
        @PathVariable jobId: UUID,
        @PathVariable resourceType: String,
    ): ResponseEntity<FileSystemResource> {
        val path = exportService.download(securityPrincipal(authentication), jobId, resourceType)
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(FHIR_NDJSON))
            .body(FileSystemResource(path))
    }

    private fun securityPrincipal(authentication: Authentication): SecurityPrincipal =
        authentication.principal as? SecurityPrincipal
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Security principal is not available")

    private fun ExportJob.toResponse(files: List<ExportJobFile>): ExportJobResponse =
        ExportJobResponse(
            id = id.toString(),
            organizationId = organizationId.value.toString(),
            status = status.dbValue,
            requestedAt = requestedAt,
            startedAt = startedAt,
            completedAt = completedAt,
            errorMessage = errorMessage,
            files = files.map { file ->
                ExportJobFileResponse(
                    resourceType = file.resourceType,
                    resourceCount = file.resourceCount,
                    url = ServletUriComponentsBuilder.fromCurrentContextPath()
                        .path("/api/v1/export-jobs/{jobId}/files/{type}")
                        .buildAndExpand(id, file.resourceType)
                        .toUriString(),
                )
            },
        )
}

data class ExportJobFileResponse(
    val resourceType: String,
    val resourceCount: Int,
    val url: String,
)

data class ExportJobResponse(
    val id: String,
    val organizationId: String,
    val status: String,
    val requestedAt: Instant,
    val startedAt: Instant?,
    val completedAt: Instant?,
    val errorMessage: String?,
    val files: List<ExportJobFileResponse>,
)
