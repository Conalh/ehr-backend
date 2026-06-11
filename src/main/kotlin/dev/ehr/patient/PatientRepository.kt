package dev.ehr.patient

import dev.ehr.identity.OrganizationId
import dev.ehr.identity.TenantScope
import dev.ehr.identity.UserId
import dev.ehr.terminology.CodeableConceptId
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.sql.Date
import java.util.UUID

@Repository
class PatientRepository(
    private val jdbcTemplate: JdbcTemplate,
) {
    fun create(command: PatientCreateCommand): Patient =
        jdbcTemplate.queryForObject(
            """
            insert into patients (
              organization_id,
              status,
              given_name,
              family_name,
              birth_date,
              administrative_gender,
              created_by,
              updated_by
            )
            values (?, ?, ?, ?, ?, ?, ?, ?)
            returning
              id,
              organization_id,
              status,
              given_name,
              family_name,
              birth_date,
              administrative_gender,
              version,
              created_at,
              updated_at,
              created_by,
              updated_by
            """.trimIndent(),
            patientRowMapper,
            command.organizationId.value,
            command.status.dbValue,
            command.givenName,
            command.familyName,
            command.birthDate?.let(Date::valueOf),
            command.administrativeGender?.dbValue,
            command.createdBy?.value,
            (command.updatedBy ?: command.createdBy)?.value,
        )!!

    fun findById(
        tenantScope: TenantScope,
        patientId: PatientId,
    ): Patient? =
        jdbcTemplate.query(
            """
            select
              id,
              organization_id,
              status,
              given_name,
              family_name,
              birth_date,
              administrative_gender,
              version,
              created_at,
              updated_at,
              created_by,
              updated_by
            from patients
            where organization_id = ?
              and id = ?
            """.trimIndent(),
            patientRowMapper,
            tenantScope.organizationId.value,
            patientId.value,
        ).singleOrNull()

    /** Most recent patients for the synthetic launch picker. */
    fun findRecentByOrganization(
        tenantScope: TenantScope,
        limit: Int = 50,
    ): List<Patient> =
        jdbcTemplate.query(
            """
            select
              id,
              organization_id,
              status,
              given_name,
              family_name,
              birth_date,
              administrative_gender,
              version,
              created_at,
              updated_at,
              created_by,
              updated_by
            from patients
            where organization_id = ?
            order by created_at desc, id
            limit ?
            """.trimIndent(),
            patientRowMapper,
            tenantScope.organizationId.value,
            limit,
        )

    fun findByIdentifier(
        tenantScope: TenantScope,
        system: String,
        value: String,
    ): Patient? =
        jdbcTemplate.query(
            """
            select
              p.id,
              p.organization_id,
              p.status,
              p.given_name,
              p.family_name,
              p.birth_date,
              p.administrative_gender,
              p.version,
              p.created_at,
              p.updated_at,
              p.created_by,
              p.updated_by
            from patient_identifiers pi
            join patients p on p.organization_id = pi.organization_id
              and p.id = pi.patient_id
            where pi.organization_id = ?
              and pi.system = ?
              and pi.value = ?
            """.trimIndent(),
            patientRowMapper,
            tenantScope.organizationId.value,
            system,
            value,
        ).singleOrNull()

    fun addIdentifier(
        tenantScope: TenantScope,
        patientId: PatientId,
        command: PatientIdentifierCreateCommand,
    ): PatientIdentifier =
        jdbcTemplate.queryForObject(
            """
            insert into patient_identifiers (
              organization_id,
              patient_id,
              system,
              value,
              use,
              type_concept_id,
              assigner_text,
              period_start,
              period_end
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
              ?
            from patients p
            where p.organization_id = ?
              and p.id = ?
            returning
              id,
              organization_id,
              patient_id,
              system,
              value,
              use,
              type_concept_id,
              assigner_text,
              period_start,
              period_end,
              created_at
            """.trimIndent(),
            identifierRowMapper,
            command.system,
            command.value,
            command.use?.dbValue,
            command.typeConceptId?.value,
            command.assignerText,
            command.periodStart?.let(Date::valueOf),
            command.periodEnd?.let(Date::valueOf),
            tenantScope.organizationId.value,
            patientId.value,
        )!!

    fun findIdentifiers(
        tenantScope: TenantScope,
        patientId: PatientId,
    ): List<PatientIdentifier> =
        jdbcTemplate.query(
            """
            select
              id,
              organization_id,
              patient_id,
              system,
              value,
              use,
              type_concept_id,
              assigner_text,
              period_start,
              period_end,
              created_at
            from patient_identifiers
            where organization_id = ?
              and patient_id = ?
            order by created_at, id
            """.trimIndent(),
            identifierRowMapper,
            tenantScope.organizationId.value,
            patientId.value,
        )

    private companion object {
        val patientRowMapper = RowMapper { rs: ResultSet, _: Int ->
            Patient(
                id = PatientId(rs.getObject("id", UUID::class.java)),
                organizationId = OrganizationId(rs.getObject("organization_id", UUID::class.java)),
                status = PatientStatus.fromDb(rs.getString("status")),
                givenName = rs.getString("given_name"),
                familyName = rs.getString("family_name"),
                birthDate = rs.getDate("birth_date")?.toLocalDate(),
                administrativeGender = rs.getString("administrative_gender")?.let(PatientAdministrativeGender::fromDb),
                version = rs.getInt("version"),
                createdAt = rs.getTimestamp("created_at").toInstant(),
                updatedAt = rs.getTimestamp("updated_at").toInstant(),
                createdBy = rs.getObject("created_by", UUID::class.java)?.let(::UserId),
                updatedBy = rs.getObject("updated_by", UUID::class.java)?.let(::UserId),
            )
        }

        val identifierRowMapper = RowMapper { rs: ResultSet, _: Int ->
            PatientIdentifier(
                id = PatientIdentifierId(rs.getObject("id", UUID::class.java)),
                organizationId = OrganizationId(rs.getObject("organization_id", UUID::class.java)),
                patientId = PatientId(rs.getObject("patient_id", UUID::class.java)),
                system = rs.getString("system"),
                value = rs.getString("value"),
                use = rs.getString("use")?.let(IdentifierUse::fromDb),
                typeConceptId = rs.getObject("type_concept_id", UUID::class.java)?.let(::CodeableConceptId),
                assignerText = rs.getString("assigner_text"),
                periodStart = rs.getDate("period_start")?.toLocalDate(),
                periodEnd = rs.getDate("period_end")?.toLocalDate(),
                createdAt = rs.getTimestamp("created_at").toInstant(),
            )
        }
    }
}
