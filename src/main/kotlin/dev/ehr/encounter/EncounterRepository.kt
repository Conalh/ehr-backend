package dev.ehr.encounter

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

class StaleEncounterTransitionException(message: String) : RuntimeException(message)

@Repository
class EncounterRepository(
    private val jdbcTemplate: JdbcTemplate,
) {
    fun create(command: EncounterCreateCommand): Encounter {
        require(command.status == EncounterStatus.PLANNED || command.status == EncounterStatus.IN_PROGRESS) {
            "encounters can only be created as planned or in-progress"
        }
        return jdbcTemplate.query(
            """
            insert into encounters (
              organization_id,
              patient_id,
              status,
              class_concept_id,
              period_start,
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
              ?
            from patients p
            where p.organization_id = ?
              and p.id = ?
            returning $COLUMNS
            """.trimIndent(),
            rowMapper,
            command.status.dbValue,
            command.classConceptId.value,
            Timestamp.from(command.periodStart),
            command.createdBy?.value,
            command.createdBy?.value,
            command.organizationId.value,
            command.patientId.value,
        ).singleOrNull()
            ?: throw IllegalArgumentException("patient does not exist in the requested organization")
    }

    fun findById(
        tenantScope: TenantScope,
        encounterId: EncounterId,
    ): Encounter? =
        jdbcTemplate.query(
            """
            select $COLUMNS
            from encounters
            where organization_id = ?
              and id = ?
            """.trimIndent(),
            rowMapper,
            tenantScope.organizationId.value,
            encounterId.value,
        ).singleOrNull()

    fun findByPatient(
        tenantScope: TenantScope,
        patientId: PatientId,
    ): List<Encounter> =
        jdbcTemplate.query(
            """
            select $COLUMNS
            from encounters
            where organization_id = ?
              and patient_id = ?
            order by period_start desc, id
            """.trimIndent(),
            rowMapper,
            tenantScope.organizationId.value,
            patientId.value,
        )

    fun transition(
        tenantScope: TenantScope,
        encounterId: EncounterId,
        command: EncounterTransitionCommand,
    ): Encounter? {
        val current = findById(tenantScope, encounterId) ?: return null
        require(current.status.canTransitionTo(command.targetStatus)) {
            "encounter status ${current.status.dbValue} cannot transition to ${command.targetStatus.dbValue}"
        }
        if (command.targetStatus == EncounterStatus.FINISHED) {
            requireNotNull(command.periodEnd ?: current.periodEnd) {
                "finishing an encounter requires a period end"
            }
        }

        return jdbcTemplate.query(
            """
            update encounters
            set status = ?,
                period_end = coalesce(?, period_end),
                version = version + 1,
                updated_at = now(),
                updated_by = coalesce(?, updated_by)
            where organization_id = ?
              and id = ?
              and status = ?
              and version = ?
            returning $COLUMNS
            """.trimIndent(),
            rowMapper,
            command.targetStatus.dbValue,
            command.periodEnd?.let(Timestamp::from),
            command.updatedBy?.value,
            tenantScope.organizationId.value,
            encounterId.value,
            current.status.dbValue,
            command.expectedVersion ?: current.version,
        ).singleOrNull()
            ?: throw StaleEncounterTransitionException(
                "encounter was modified concurrently; transition not applied",
            )
    }

    private companion object {
        const val COLUMNS = """
              id,
              organization_id,
              patient_id,
              status,
              class_concept_id,
              period_start,
              period_end,
              version,
              created_at,
              updated_at,
              created_by,
              updated_by
        """

        val rowMapper = RowMapper { rs: ResultSet, _: Int ->
            Encounter(
                id = EncounterId(rs.getObject("id", UUID::class.java)),
                organizationId = OrganizationId(rs.getObject("organization_id", UUID::class.java)),
                patientId = PatientId(rs.getObject("patient_id", UUID::class.java)),
                status = EncounterStatus.fromDb(rs.getString("status")),
                classConceptId = CodeableConceptId(rs.getObject("class_concept_id", UUID::class.java)),
                periodStart = rs.getTimestamp("period_start").toInstant(),
                periodEnd = rs.getTimestamp("period_end")?.toInstant(),
                version = rs.getInt("version"),
                createdAt = rs.getTimestamp("created_at").toInstant(),
                updatedAt = rs.getTimestamp("updated_at").toInstant(),
                createdBy = rs.getObject("created_by", UUID::class.java)?.let(::UserId),
                updatedBy = rs.getObject("updated_by", UUID::class.java)?.let(::UserId),
            )
        }
    }
}
