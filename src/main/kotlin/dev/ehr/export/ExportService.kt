package dev.ehr.export

import dev.ehr.identity.TenantScope
import dev.ehr.security.AuditEventService
import dev.ehr.security.AuditOperation
import dev.ehr.security.AuditOutcome
import dev.ehr.security.PolicyEvaluationRequest
import dev.ehr.security.PolicyEvaluator
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
    private val policyEvaluator: PolicyEvaluator,
    private val auditEventService: AuditEventService,
    private val exportJobRepository: ExportJobRepository,
    private val exportJobProcessor: ExportJobProcessor,
) {
    fun request(principal: SecurityPrincipal): ExportJob {
        val decision = evaluate(principal, PolicyOperation.WRITE)
        if (!decision.allowed) {
            auditEventService.recordDeniedAccess(decision)
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Not authorized to request exports")
        }

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
        val decision = evaluate(principal, PolicyOperation.READ)
        if (!decision.allowed) {
            auditEventService.recordDeniedAccess(decision, resourceId = jobId)
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Not authorized to read exports")
        }

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
        val decision = evaluate(principal, PolicyOperation.READ)
        if (!decision.allowed) {
            auditEventService.recordDeniedAccess(decision, resourceId = jobId)
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Not authorized to download exports")
        }

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

    private fun evaluate(
        principal: SecurityPrincipal,
        operation: PolicyOperation,
    ) = policyEvaluator.evaluate(
        principal = principal,
        request = PolicyEvaluationRequest(
            resourceType = PolicyResourceType.EXPORT,
            operation = operation,
            organizationId = principal.organization.organizationId,
        ),
    )

    private fun tenantScope(principal: SecurityPrincipal): TenantScope =
        TenantScope(principal.organization.organizationId)
}
