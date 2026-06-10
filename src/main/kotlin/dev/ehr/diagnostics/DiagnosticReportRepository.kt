package dev.ehr.diagnostics

import dev.ehr.encounter.EncounterId
import dev.ehr.identity.OrganizationId
import dev.ehr.identity.TenantScope
import dev.ehr.identity.UserId
import dev.ehr.observation.ObservationId
import dev.ehr.order.OrderId
import dev.ehr.patient.PatientId
import dev.ehr.terminology.CodeableConceptId
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.util.UUID

@Repository
class DiagnosticReportRepository(
    private val jdbcTemplate: JdbcTemplate,
) {
    fun create(command: DiagnosticReportCreateCommand): DiagnosticReport {
        val header = jdbcTemplate.queryForObject(
            """
            insert into diagnostic_reports (
              organization_id,
              patient_id,
              encounter_id,
              order_id,
              status,
              code_concept_id,
              conclusion_text,
              created_by,
              updated_by
            )
            values (?, ?, ?, ?, ?, ?, ?, ?, ?)
            returning $COLUMNS
            """.trimIndent(),
            headerRowMapper,
            command.organizationId.value,
            command.patientId.value,
            command.encounterId?.value,
            command.orderId.value,
            command.status.dbValue,
            command.codeConceptId.value,
            command.conclusionText,
            command.createdBy?.value,
            command.createdBy?.value,
        )!!

        command.resultObservationIds.forEachIndexed { index, observationId ->
            jdbcTemplate.update(
                """
                insert into diagnostic_report_results (
                  diagnostic_report_id, organization_id, observation_id, ordinal
                )
                values (?, ?, ?, ?)
                """.trimIndent(),
                header.id.value,
                command.organizationId.value,
                observationId.value,
                index,
            )
        }

        return header.copy(resultObservationIds = command.resultObservationIds)
    }

    fun findById(
        tenantScope: TenantScope,
        reportId: DiagnosticReportId,
    ): DiagnosticReport? {
        val header = jdbcTemplate.query(
            """
            select $COLUMNS
            from diagnostic_reports
            where organization_id = ?
              and id = ?
            """.trimIndent(),
            headerRowMapper,
            tenantScope.organizationId.value,
            reportId.value,
        ).singleOrNull() ?: return null

        return header.copy(resultObservationIds = resultIds(header.id))
    }

    fun findByPatient(
        tenantScope: TenantScope,
        patientId: PatientId,
    ): List<DiagnosticReport> =
        jdbcTemplate.query(
            """
            select $COLUMNS
            from diagnostic_reports
            where organization_id = ?
              and patient_id = ?
            order by issued_at desc, id
            """.trimIndent(),
            headerRowMapper,
            tenantScope.organizationId.value,
            patientId.value,
        ).map { header -> header.copy(resultObservationIds = resultIds(header.id)) }

    private fun resultIds(reportId: DiagnosticReportId): List<ObservationId> =
        jdbcTemplate.query(
            """
            select observation_id
            from diagnostic_report_results
            where diagnostic_report_id = ?
            order by ordinal
            """.trimIndent(),
            { rs, _ -> ObservationId(rs.getObject("observation_id", UUID::class.java)) },
            reportId.value,
        )

    private companion object {
        const val COLUMNS = """
              id,
              organization_id,
              patient_id,
              encounter_id,
              order_id,
              status,
              code_concept_id,
              conclusion_text,
              issued_at,
              version,
              created_at,
              updated_at,
              created_by,
              updated_by
        """

        val headerRowMapper = RowMapper { rs: ResultSet, _: Int ->
            DiagnosticReport(
                id = DiagnosticReportId(rs.getObject("id", UUID::class.java)),
                organizationId = OrganizationId(rs.getObject("organization_id", UUID::class.java)),
                patientId = PatientId(rs.getObject("patient_id", UUID::class.java)),
                encounterId = rs.getObject("encounter_id", UUID::class.java)?.let(::EncounterId),
                orderId = OrderId(rs.getObject("order_id", UUID::class.java)),
                status = DiagnosticReportStatus.fromDb(rs.getString("status")),
                codeConceptId = CodeableConceptId(rs.getObject("code_concept_id", UUID::class.java)),
                conclusionText = rs.getString("conclusion_text"),
                issuedAt = rs.getTimestamp("issued_at").toInstant(),
                resultObservationIds = emptyList(),
                version = rs.getInt("version"),
                createdAt = rs.getTimestamp("created_at").toInstant(),
                updatedAt = rs.getTimestamp("updated_at").toInstant(),
                createdBy = rs.getObject("created_by", UUID::class.java)?.let(::UserId),
                updatedBy = rs.getObject("updated_by", UUID::class.java)?.let(::UserId),
            )
        }
    }
}
