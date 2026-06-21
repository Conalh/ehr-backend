package dev.ehr.export

import dev.ehr.security.AccessAuthorizer
import dev.ehr.security.AuditEventService
import dev.ehr.security.AuditOperation
import dev.ehr.security.AuditOutcome
import dev.ehr.security.PolicyOperation
import dev.ehr.security.PolicyResourceType
import dev.ehr.security.SecurityPrincipal
import dev.ehr.security.tenantScope
import org.springframework.core.task.TaskRejectedException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.UUID

@Service
class ExportService(
    private val accessAuthorizer: AccessAuthorizer,
    private val auditEventService: AuditEventService,
    private val exportJobRepository: ExportJobRepository,
    private val exportJobDispatcher: ExportJobDispatcher,
) {
    fun request(principal: SecurityPrincipal): ExportJob {
        val decision = authorize(principal, PolicyOperation.WRITE, "Not authorized to request exports")

        val job = exportJobRepository.create(
            organizationId = principal.organization.organizationId,
            requestedBy = principal.subject.userId,
        )
        try {
            exportJobDispatcher.dispatch(job)
        } catch (exception: TaskRejectedException) {
            exportJobRepository.markFailed(
                jobId = job.id,
                errorMessage = "export scheduling failed (${exception.javaClass.simpleName})",
            )
            auditEventService.recordFailedAccess(
                decision = decision,
                operation = AuditOperation.EXPORT,
                resourceId = job.id,
            )
            auditEventService.recordBackgroundEvent(
                organizationId = job.organizationId,
                subjectUserId = job.requestedBy,
                resourceType = "EXPORT_JOB",
                operation = AuditOperation.SYSTEM,
                outcome = AuditOutcome.FAILURE,
                resourceId = job.id,
                correlationId = null,
            )
            throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Export capacity is temporarily unavailable")
        }
        auditEventService.recordSuccessfulAccess(
            decision = decision,
            operation = AuditOperation.EXPORT,
            resourceId = job.id,
        )
        return job
    }

    fun status(
        principal: SecurityPrincipal,
        jobId: UUID,
    ): Pair<ExportJob, List<ExportJobFile>> {
        val decision = authorize(principal, PolicyOperation.READ, "Not authorized to read exports", resourceId = jobId)

        val scope = principal.tenantScope()
        val job = exportJobRepository.findById(scope, jobId)
        if (job == null) {
            auditEventService.recordFailedAccess(
                decision = decision,
                operation = AuditOperation.READ,
                resourceId = jobId,
            )
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Export job not found")
        }

        auditEventService.recordSuccessfulAccess(
            decision = decision,
            operation = AuditOperation.READ,
            resourceId = job.id,
        )
        val files = if (job.status == ExportJobStatus.COMPLETED) {
            exportJobRepository.findFiles(scope, jobId)
        } else {
            emptyList()
        }
        return job to files
    }

    fun download(
        principal: SecurityPrincipal,
        jobId: UUID,
        resourceType: String,
    ): Path {
        val decision = authorize(principal, PolicyOperation.READ, "Not authorized to download exports", resourceId = jobId)

        val scope = principal.tenantScope()
        val job = exportJobRepository.findById(scope, jobId)
        val file = job
            ?.takeIf { it.status == ExportJobStatus.COMPLETED }
            ?.let { exportJobRepository.findFiles(scope, jobId) }
            ?.singleOrNull { it.resourceType == resourceType }
        if (file == null) {
            auditEventService.recordFailedAccess(
                decision = decision,
                operation = AuditOperation.EXPORT,
                resourceId = jobId,
            )
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Export file not found")
        }

        val path = Paths.get(file.storagePath)
        if (!Files.exists(path)) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Export file is no longer available")
        }

        auditEventService.recordSuccessfulAccess(
            decision = decision,
            operation = AuditOperation.EXPORT,
            resourceId = file.id,
        )
        return path
    }

    private fun authorize(
        principal: SecurityPrincipal,
        operation: PolicyOperation,
        forbiddenMessage: String,
        resourceId: UUID? = null,
    ) = accessAuthorizer.authorize(
        principal = principal,
        resourceType = PolicyResourceType.EXPORT,
        operation = operation,
        forbiddenMessage = forbiddenMessage,
        resourceId = resourceId,
    )

}
