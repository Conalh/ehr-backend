package dev.ehr.medication

import dev.ehr.encounter.EncounterId
import dev.ehr.identity.OrganizationId
import dev.ehr.identity.TenantScope
import dev.ehr.identity.UserId
import dev.ehr.patient.PatientId
import dev.ehr.terminology.CodeableConceptId
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import java.sql.Date
import java.sql.ResultSet
import java.util.UUID

@Repository
class MedicationStatementRepository(
    private val jdbcTemplate: JdbcTemplate,
) {
    fun create(command: MedicationStatementCreateCommand): MedicationStatement =
        jdbcTemplate.query(
            """
            insert into medication_statements (
              organization_id,
              patient_id,
              encounter_id,
              status,
              medication_concept_id,
              dosage_text,
              effective_start,
              effective_end,
              created_by,
              updated_by
            )
            select
              p.organization_id,
              p.id,
              ?,
              ?,
              ?,
              ?,
              ?,
              ?,
              ?,
              ?
            from patients p
            where p.organization_id = ?
              and p.id = ?
            returning $COLUMNS
            """.trimIndent(),
            rowMapper,
            command.encounterId?.value,
            command.status.dbValue,
            command.medicationConceptId.value,
            command.dosageText,
            command.effectiveStart?.let(Date::valueOf),
            command.effectiveEnd?.let(Date::valueOf),
            command.createdBy?.value,
            command.createdBy?.value,
            command.organizationId.value,
            command.patientId.value,
        ).singleOrNull()
            ?: throw IllegalArgumentException("patient does not exist in the requested organization")

    fun findById(
        tenantScope: TenantScope,
        medicationStatementId: MedicationStatementId,
    ): MedicationStatement? =
        jdbcTemplate.query(
            """
            select $COLUMNS
            from medication_statements
            where organization_id = ?
              and id = ?
            """.trimIndent(),
            rowMapper,
            tenantScope.organizationId.value,
            medicationStatementId.value,
        ).singleOrNull()

    fun findByPatient(
        tenantScope: TenantScope,
        patientId: PatientId,
    ): List<MedicationStatement> =
        jdbcTemplate.query(
            """
            select $COLUMNS
            from medication_statements
            where organization_id = ?
              and patient_id = ?
            order by recorded_at desc, id
            """.trimIndent(),
            rowMapper,
            tenantScope.organizationId.value,
            patientId.value,
        )

    private companion object {
        const val COLUMNS = """
              id,
              organization_id,
              patient_id,
              encounter_id,
              status,
              medication_concept_id,
              dosage_text,
              effective_start,
              effective_end,
              recorded_at,
              version,
              created_at,
              updated_at,
              created_by,
              updated_by
        """

        val rowMapper = RowMapper { rs: ResultSet, _: Int ->
            MedicationStatement(
                id = MedicationStatementId(rs.getObject("id", UUID::class.java)),
                organizationId = OrganizationId(rs.getObject("organization_id", UUID::class.java)),
                patientId = PatientId(rs.getObject("patient_id", UUID::class.java)),
                encounterId = rs.getObject("encounter_id", UUID::class.java)?.let(::EncounterId),
                status = MedicationStatementStatus.fromDb(rs.getString("status")),
                medicationConceptId = CodeableConceptId(rs.getObject("medication_concept_id", UUID::class.java)),
                dosageText = rs.getString("dosage_text"),
                effectiveStart = rs.getDate("effective_start")?.toLocalDate(),
                effectiveEnd = rs.getDate("effective_end")?.toLocalDate(),
                recordedAt = rs.getTimestamp("recorded_at").toInstant(),
                version = rs.getInt("version"),
                createdAt = rs.getTimestamp("created_at").toInstant(),
                updatedAt = rs.getTimestamp("updated_at").toInstant(),
                createdBy = rs.getObject("created_by", UUID::class.java)?.let(::UserId),
                updatedBy = rs.getObject("updated_by", UUID::class.java)?.let(::UserId),
            )
        }
    }
}
