package dev.ehr.export

import dev.ehr.identity.OrganizationId
import dev.ehr.identity.TenantScope
import dev.ehr.identity.UserId
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.util.UUID

@Repository
class ExportJobRepository(
    private val jdbcTemplate: JdbcTemplate,
) {
    fun create(
        organizationId: OrganizationId,
        requestedBy: UserId?,
    ): ExportJob =
        jdbcTemplate.queryForObject(
            """
            insert into export_jobs (organization_id, requested_by)
            values (?, ?)
            returning $JOB_COLUMNS
            """.trimIndent(),
            jobRowMapper,
            organizationId.value,
            requestedBy?.value,
        )!!

    fun findById(
        tenantScope: TenantScope,
        jobId: UUID,
    ): ExportJob? =
        jdbcTemplate.query(
            """
            select $JOB_COLUMNS
            from export_jobs
            where organization_id = ?
              and id = ?
            """.trimIndent(),
            jobRowMapper,
            tenantScope.organizationId.value,
            jobId,
        ).singleOrNull()

    fun markInProgress(jobId: UUID): Boolean =
        jdbcTemplate.update(
            """
            update export_jobs
            set status = 'in-progress', started_at = now(), version = version + 1
            where id = ? and status = 'pending'
            """.trimIndent(),
            jobId,
        ) == 1

    fun markCompleted(jobId: UUID) {
        jdbcTemplate.update(
            """
            update export_jobs
            set status = 'completed', completed_at = now(), version = version + 1
            where id = ? and status = 'in-progress'
            """.trimIndent(),
            jobId,
        )
    }

    fun markFailed(
        jobId: UUID,
        errorMessage: String,
    ) {
        jdbcTemplate.update(
            """
            update export_jobs
            set status = 'failed', completed_at = now(), error_message = ?, version = version + 1
            where id = ? and status in ('pending', 'in-progress')
            """.trimIndent(),
            errorMessage,
            jobId,
        )
    }

    fun addFile(
        organizationId: OrganizationId,
        jobId: UUID,
        resourceType: String,
        resourceCount: Int,
        storagePath: String,
    ): ExportJobFile =
        jdbcTemplate.queryForObject(
            """
            insert into export_job_files (export_job_id, organization_id, resource_type, resource_count, storage_path)
            values (?, ?, ?, ?, ?)
            returning $FILE_COLUMNS
            """.trimIndent(),
            fileRowMapper,
            jobId,
            organizationId.value,
            resourceType,
            resourceCount,
            storagePath,
        )!!

    fun findFiles(
        tenantScope: TenantScope,
        jobId: UUID,
    ): List<ExportJobFile> =
        jdbcTemplate.query(
            """
            select $FILE_COLUMNS
            from export_job_files
            where organization_id = ?
              and export_job_id = ?
            order by resource_type
            """.trimIndent(),
            fileRowMapper,
            tenantScope.organizationId.value,
            jobId,
        )

    private companion object {
        const val JOB_COLUMNS = """
              id, organization_id, status, requested_by, requested_at,
              started_at, completed_at, error_message, version
        """
        const val FILE_COLUMNS = """
              id, export_job_id, organization_id, resource_type,
              resource_count, storage_path, created_at
        """

        val jobRowMapper = RowMapper { rs: ResultSet, _: Int ->
            ExportJob(
                id = rs.getObject("id", UUID::class.java),
                organizationId = OrganizationId(rs.getObject("organization_id", UUID::class.java)),
                status = ExportJobStatus.fromDb(rs.getString("status")),
                requestedBy = rs.getObject("requested_by", UUID::class.java)?.let(::UserId),
                requestedAt = rs.getTimestamp("requested_at").toInstant(),
                startedAt = rs.getTimestamp("started_at")?.toInstant(),
                completedAt = rs.getTimestamp("completed_at")?.toInstant(),
                errorMessage = rs.getString("error_message"),
                version = rs.getInt("version"),
            )
        }

        val fileRowMapper = RowMapper { rs: ResultSet, _: Int ->
            ExportJobFile(
                id = rs.getObject("id", UUID::class.java),
                exportJobId = rs.getObject("export_job_id", UUID::class.java),
                organizationId = OrganizationId(rs.getObject("organization_id", UUID::class.java)),
                resourceType = rs.getString("resource_type"),
                resourceCount = rs.getInt("resource_count"),
                storagePath = rs.getString("storage_path"),
                createdAt = rs.getTimestamp("created_at").toInstant(),
            )
        }
    }
}
