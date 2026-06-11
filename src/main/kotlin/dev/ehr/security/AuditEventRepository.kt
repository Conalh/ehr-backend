package dev.ehr.security

import dev.ehr.identity.OAuthClientId
import dev.ehr.identity.OrganizationId
import dev.ehr.identity.UserId
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.util.UUID

@Repository
class AuditEventRepository(
    private val jdbcTemplate: JdbcTemplate,
) {
    fun append(command: AuditEventCommand): AuditEventRecord =
        jdbcTemplate.queryForObject(
            """
            insert into audit_events (
              organization_id,
              subject_user_id,
              client_id,
              patient_id,
              resource_type,
              resource_id,
              operation,
              outcome,
              policy_version,
              policy_reason_code,
              relationship_basis,
              correlation_id,
              metadata
            )
            values (?, ?, null, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
            returning
              id,
              occurred_at,
              organization_id,
              subject_user_id,
              client_id,
              patient_id,
              resource_type,
              resource_id,
              operation,
              outcome,
              policy_version,
              policy_reason_code,
              relationship_basis,
              correlation_id,
              metadata::text as metadata
            """.trimIndent(),
            rowMapper,
            command.organizationId?.value,
            command.subjectUserId?.value,
            command.patientId,
            command.resourceType,
            command.resourceId,
            command.operation.dbValue,
            command.outcome.dbValue,
            command.policyVersion,
            command.policyReasonCode,
            command.relationshipBasis,
            command.correlationId,
            command.metadata,
        )!!

    private companion object {
        val rowMapper = RowMapper { rs: ResultSet, _: Int ->
            AuditEventRecord(
                id = rs.getObject("id", UUID::class.java),
                occurredAt = rs.getTimestamp("occurred_at").toInstant(),
                organizationId = rs.getObject("organization_id", UUID::class.java)?.let(::OrganizationId),
                subjectUserId = rs.getObject("subject_user_id", UUID::class.java)?.let(::UserId),
                clientId = rs.getObject("client_id", UUID::class.java)?.let(::OAuthClientId),
                patientId = rs.getObject("patient_id", UUID::class.java),
                resourceType = rs.getString("resource_type"),
                resourceId = rs.getObject("resource_id", UUID::class.java),
                operation = AuditOperation.valueOf(rs.getString("operation")),
                outcome = AuditOutcome.valueOf(rs.getString("outcome")),
                policyVersion = rs.getString("policy_version"),
                policyReasonCode = rs.getString("policy_reason_code"),
                relationshipBasis = rs.getString("relationship_basis"),
                correlationId = rs.getString("correlation_id"),
                metadata = rs.getString("metadata"),
            )
        }
    }
}
