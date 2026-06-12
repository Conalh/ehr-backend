package dev.ehr.provenance

import dev.ehr.identity.OAuthClientId
import dev.ehr.identity.OrganizationId
import dev.ehr.identity.TenantScope
import dev.ehr.identity.UserId
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.util.UUID

@Repository
class ProvenanceRepository(
    private val jdbcTemplate: JdbcTemplate,
) {
    fun append(command: ProvenanceEventCommand): ProvenanceEvent =
        jdbcTemplate.queryForObject(
            """
            insert into provenance_events (
              organization_id,
              patient_id,
              target_resource_type,
              target_resource_id,
              target_version,
              activity,
              agent_user_id,
              agent_client_id,
              source_type,
              source_reference,
              prior_resource_version,
              synthetic_generation_run_id
            )
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            returning $COLUMNS
            """.trimIndent(),
            rowMapper,
            command.organizationId.value,
            command.patientId,
            command.targetResourceType,
            command.targetResourceId,
            command.targetVersion,
            command.activity.dbValue,
            command.agentUserId?.value,
            command.agentClientId?.value,
            command.sourceType.dbValue,
            command.sourceReference,
            command.priorResourceVersion,
            command.syntheticGenerationRunId,
        )!!

    fun findById(
        tenantScope: TenantScope,
        provenanceId: UUID,
    ): ProvenanceEvent? =
        jdbcTemplate.query(
            """
            select $COLUMNS
            from provenance_events
            where organization_id = ?
              and id = ?
            """.trimIndent(),
            rowMapper,
            tenantScope.organizationId.value,
            provenanceId,
        ).singleOrNull()

    fun findByTarget(
        tenantScope: TenantScope,
        targetResourceType: String,
        targetResourceId: UUID,
    ): List<ProvenanceEvent> =
        jdbcTemplate.query(
            """
            select $COLUMNS
            from provenance_events
            where organization_id = ?
              and target_resource_type = ?
              and target_resource_id = ?
            order by target_version, recorded_at
            """.trimIndent(),
            rowMapper,
            tenantScope.organizationId.value,
            targetResourceType,
            targetResourceId,
        )

    /** Batch lookup for _revinclude=Provenance:target. */
    fun findByTargets(
        tenantScope: TenantScope,
        targetResourceType: String,
        targetResourceIds: List<UUID>,
    ): List<ProvenanceEvent> {
        if (targetResourceIds.isEmpty()) {
            return emptyList()
        }
        return jdbcTemplate.query(
            { connection ->
                connection.prepareStatement(
                    """
                    select $COLUMNS
                    from provenance_events
                    where organization_id = ?
                      and target_resource_type = ?
                      and target_resource_id = any (?)
                    order by recorded_at, id
                    """.trimIndent(),
                ).apply {
                    setObject(1, tenantScope.organizationId.value)
                    setString(2, targetResourceType)
                    setArray(3, connection.createArrayOf("uuid", targetResourceIds.toTypedArray()))
                }
            },
            rowMapper,
        )
    }

    fun findByPatient(
        tenantScope: TenantScope,
        patientId: UUID,
    ): List<ProvenanceEvent> =
        jdbcTemplate.query(
            """
            select $COLUMNS
            from provenance_events
            where organization_id = ?
              and patient_id = ?
            order by recorded_at desc, id
            """.trimIndent(),
            rowMapper,
            tenantScope.organizationId.value,
            patientId,
        )

    private companion object {
        const val COLUMNS = """
              id,
              organization_id,
              patient_id,
              target_resource_type,
              target_resource_id,
              target_version,
              activity,
              agent_user_id,
              agent_client_id,
              recorded_at,
              source_type,
              source_reference,
              prior_resource_version,
              synthetic_generation_run_id
        """

        val rowMapper = RowMapper { rs: ResultSet, _: Int ->
            ProvenanceEvent(
                id = rs.getObject("id", UUID::class.java),
                organizationId = OrganizationId(rs.getObject("organization_id", UUID::class.java)),
                patientId = rs.getObject("patient_id", UUID::class.java),
                targetResourceType = rs.getString("target_resource_type"),
                targetResourceId = rs.getObject("target_resource_id", UUID::class.java),
                targetVersion = rs.getInt("target_version"),
                activity = ProvenanceActivity.fromDb(rs.getString("activity")),
                agentUserId = rs.getObject("agent_user_id", UUID::class.java)?.let(::UserId),
                agentClientId = rs.getObject("agent_client_id", UUID::class.java)?.let(::OAuthClientId),
                recordedAt = rs.getTimestamp("recorded_at").toInstant(),
                sourceType = ProvenanceSourceType.fromDb(rs.getString("source_type")),
                sourceReference = rs.getString("source_reference"),
                priorResourceVersion = rs.getObject("prior_resource_version") as Int?,
                syntheticGenerationRunId = rs.getObject("synthetic_generation_run_id", UUID::class.java),
            )
        }
    }
}
