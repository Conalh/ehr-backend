package dev.ehr.export

import dev.ehr.identity.TenantScope
import dev.ehr.security.AccessAuthorizer
import dev.ehr.security.AuditEventService
import dev.ehr.security.AuditOperation
import dev.ehr.security.AuditOutcome
import dev.ehr.security.PolicyOperation
import dev.ehr.security.PolicyResourceType
import dev.ehr.security.SecurityPrincipal
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
    private val exportJobProcessor: ExportJobProcessor,
) {
    fun request(principal: SecurityPrincipal): ExportJob {
        val decision = authorize(principal, PolicyOperation.WRITE, "Not authorized to request exports")

        val job = exportJobRepository.create(
            organizationId = principal.organization.organizationId,
            requestedBy = principal.subject.userId,
        )
        auditEventService.recordResourceAccess(
            decision = decision,
            operation = AuditOperation.EXPORT,
            outcome = AuditOutcome.SUCCESS,
            resourceId = job.id,
        )
        exportJobProcessor.processAsync(job)
        return job
    }

    fun status(
        principal: SecurityPrincipal,
        jobId: UUID,
    ): Pair<ExportJob, List<ExportJobFile>> {
        val decision = authorize(principal, PolicyOperation.READ, "Not authorized to read exports", resourceId = jobId)

        val scope = tenantScope(principal)
        val job = exportJobRepository.findById(scope, jobId)
        if (job == null) {
            auditEventService.recordResourceAccess(
                decision = decision,
                operation = AuditOperation.READ,
                outcome = AuditOutcome.FAILURE,
                resourceId = jobId,
            )
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Export job not found")
        }

        auditEventService.recordResourceAccess(
            decision = decision,
            operation = AuditOperation.READ,
            outcome = AuditOutcome.SUCCESS,
            resourceId = job.id,
        )
        return job to exportJobRepository.findFiles(scope, jobId)
    }

    fun download(
        principal: SecurityPrincipal,
        jobId: UUID,
        resourceType: String,
    ): Path {
        val decision = authorize(principal, PolicyOperation.READ, "Not authorized to download exports", resourceId = jobId)

        val scope = tenantScope(principal)
        val file = exportJobRepository.findById(scope, jobId)
            ?.let { exportJobRepository.findFiles(scope, jobId) }
            ?.singleOrNull { it.resourceType == resourceType }
        if (file == null) {
            auditEventService.recordResourceAccess(
                decision = decision,
                operation = AuditOperation.EXPORT,
                outcome = AuditOutcome.FAILURE,
                resourceId = jobId,
            )
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Export file not found")
        }

        val path = Paths.get(file.storagePath)
        if (!Files.exists(path)) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Export file is no longer available")
        }

        auditEventService.recordResourceAccess(
            decision = decision,
            operation = AuditOperation.EXPORT,
            outcome = AuditOutcome.SUCCESS,
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

    private fun tenantScope(principal: SecurityPrincipal): TenantScope =
        TenantScope(principal.organization.organizationId)
}
