package dev.ehr.provenance

import dev.ehr.identity.OrganizationId
import dev.ehr.identity.TenantScope
import dev.ehr.identity.UserId
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.util.UUID

@Repository
class ResourceRevisionRepository(
    private val jdbcTemplate: JdbcTemplate,
) {
    fun append(command: ResourceRevisionCommand): ResourceRevision =
        jdbcTemplate.queryForObject(
            """
            insert into resource_revisions (
              organization_id,
              patient_id,
              resource_type,
              resource_id,
              version,
              snapshot,
              recorded_by
            )
            values (?, ?, ?, ?, ?, ?::jsonb, ?)
            returning $COLUMNS
            """.trimIndent(),
            rowMapper,
            command.organizationId.value,
            command.patientId,
            command.resourceType,
            command.resourceId,
            command.version,
            command.snapshotJson,
            command.recordedBy?.value,
        )!!

    fun findRevisions(
        tenantScope: TenantScope,
        resourceType: String,
        resourceId: UUID,
    ): List<ResourceRevision> =
        jdbcTemplate.query(
            """
            select $COLUMNS
            from resource_revisions
            where organization_id = ?
              and resource_type = ?
              and resource_id = ?
            order by version
            """.trimIndent(),
            rowMapper,
            tenantScope.organizationId.value,
            resourceType,
            resourceId,
        )

    private companion object {
        const val COLUMNS = """
              id,
              organization_id,
              patient_id,
              resource_type,
              resource_id,
              version,
              snapshot::text as snapshot,
              recorded_at,
              recorded_by
        """

        val rowMapper = RowMapper { rs: ResultSet, _: Int ->
            ResourceRevision(
                id = rs.getObject("id", UUID::class.java),
                organizationId = OrganizationId(rs.getObject("organization_id", UUID::class.java)),
                patientId = rs.getObject("patient_id", UUID::class.java),
                resourceType = rs.getString("resource_type"),
                resourceId = rs.getObject("resource_id", UUID::class.java),
                version = rs.getInt("version"),
                snapshotJson = rs.getString("snapshot"),
                recordedAt = rs.getTimestamp("recorded_at").toInstant(),
                recordedBy = rs.getObject("recorded_by", UUID::class.java)?.let(::UserId),
            )
        }
    }
}
