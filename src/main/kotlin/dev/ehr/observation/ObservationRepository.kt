package dev.ehr.observation

import dev.ehr.encounter.EncounterId
import dev.ehr.identity.OrganizationId
import dev.ehr.identity.TenantScope
import dev.ehr.identity.UserId
import dev.ehr.patient.PatientId
import dev.ehr.terminology.CodeableConceptId
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.sql.Timestamp
import java.util.UUID

@Repository
class ObservationRepository(
    private val jdbcTemplate: JdbcTemplate,
) {
    fun create(command: ObservationCreateCommand): Observation {
        val value = command.value
        return jdbcTemplate.query(
            """
            insert into observations (
              organization_id,
              patient_id,
              encounter_id,
              status,
              category,
              code_concept_id,
              value_quantity,
              value_quantity_unit,
              value_concept_id,
              value_text,
              effective_at,
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
            command.category.dbValue,
            command.codeConceptId.value,
            (value as? ObservationValue.Quantity)?.value,
            (value as? ObservationValue.Quantity)?.unit,
            (value as? ObservationValue.Coded)?.conceptId?.value,
            (value as? ObservationValue.Text)?.value,
            Timestamp.from(command.effectiveAt),
            command.createdBy?.value,
            command.createdBy?.value,
            command.organizationId.value,
            command.patientId.value,
        ).singleOrNull()
            ?: throw IllegalArgumentException("patient does not exist in the requested organization")
    }

    fun findById(
        tenantScope: TenantScope,
        observationId: ObservationId,
    ): Observation? =
        jdbcTemplate.query(
            """
            select $COLUMNS
            from observations
            where organization_id = ?
              and id = ?
            """.trimIndent(),
            rowMapper,
            tenantScope.organizationId.value,
            observationId.value,
        ).singleOrNull()

    fun findByPatient(
        tenantScope: TenantScope,
        patientId: PatientId,
        category: ObservationCategory? = null,
    ): List<Observation> =
        if (category == null) {
            jdbcTemplate.query(
                """
                select $COLUMNS
                from observations
                where organization_id = ?
                  and patient_id = ?
                order by effective_at desc, id
                """.trimIndent(),
                rowMapper,
                tenantScope.organizationId.value,
                patientId.value,
            )
        } else {
            jdbcTemplate.query(
                """
                select $COLUMNS
                from observations
                where organization_id = ?
                  and patient_id = ?
                  and category = ?
                order by effective_at desc, id
                """.trimIndent(),
                rowMapper,
                tenantScope.organizationId.value,
                patientId.value,
                category.dbValue,
            )
        }

    private companion object {
        const val COLUMNS = """
              id,
              organization_id,
              patient_id,
              encounter_id,
              status,
              category,
              code_concept_id,
              value_quantity,
              value_quantity_unit,
              value_concept_id,
              value_text,
              effective_at,
              version,
              created_at,
              updated_at,
              created_by,
              updated_by
        """

        val rowMapper = RowMapper { rs: ResultSet, _: Int ->
            Observation(
                id = ObservationId(rs.getObject("id", UUID::class.java)),
                organizationId = OrganizationId(rs.getObject("organization_id", UUID::class.java)),
                patientId = PatientId(rs.getObject("patient_id", UUID::class.java)),
                encounterId = rs.getObject("encounter_id", UUID::class.java)?.let(::EncounterId),
                status = ObservationStatus.fromDb(rs.getString("status")),
                category = ObservationCategory.fromDb(rs.getString("category")),
                codeConceptId = CodeableConceptId(rs.getObject("code_concept_id", UUID::class.java)),
                value = mapValue(rs),
                effectiveAt = rs.getTimestamp("effective_at").toInstant(),
                version = rs.getInt("version"),
                createdAt = rs.getTimestamp("created_at").toInstant(),
                updatedAt = rs.getTimestamp("updated_at").toInstant(),
                createdBy = rs.getObject("created_by", UUID::class.java)?.let(::UserId),
                updatedBy = rs.getObject("updated_by", UUID::class.java)?.let(::UserId),
            )
        }

        fun mapValue(rs: ResultSet): ObservationValue {
            val quantity = rs.getBigDecimal("value_quantity")
            if (quantity != null) {
                return ObservationValue.Quantity(value = quantity, unit = rs.getString("value_quantity_unit"))
            }
            val conceptId = rs.getObject("value_concept_id", UUID::class.java)
            if (conceptId != null) {
                return ObservationValue.Coded(CodeableConceptId(conceptId))
            }
            return ObservationValue.Text(rs.getString("value_text"))
        }
    }
}
