package dev.ehr.export

import dev.ehr.identity.OrganizationId
import dev.ehr.identity.UserId
import java.time.Instant
import java.util.UUID

enum class ExportJobStatus(val dbValue: String) {
    PENDING("pending"),
    IN_PROGRESS("in-progress"),
    COMPLETED("completed"),
    FAILED("failed"),
    ;

    companion object {
        fun fromDb(dbValue: String): ExportJobStatus =
            entries.first { it.dbValue == dbValue }
    }
}

data class ExportJob(
    val id: UUID,
    val organizationId: OrganizationId,
    val status: ExportJobStatus,
    val requestedBy: UserId?,
    val requestedAt: Instant,
    val startedAt: Instant?,
    val completedAt: Instant?,
    val errorMessage: String?,
    val version: Int,
)

data class ExportJobFile(
    val id: UUID,
    val exportJobId: UUID,
    val organizationId: OrganizationId,
    val resourceType: String,
    val resourceCount: Int,
    val storagePath: String,
    val createdAt: Instant,
)
