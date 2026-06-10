package dev.ehr.allergy

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
class AllergyRepository(
    private val jdbcTemplate: JdbcTemplate,
) {
    fun create(command: AllergyCreateCommand): Allergy =
        jdbcTemplate.query(
            """
            insert into allergies (
              organization_id,
              patient_id,
              encounter_id,
              clinical_status,
              verification_status,
              code_concept_id,
              category,
              criticality,
              onset_date,
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
              ?
            from patients p
            where p.organization_id = ?
              and p.id = ?
            returning $COLUMNS
            """.trimIndent(),
            rowMapper,
            command.encounterId?.value,
            command.clinicalStatus.dbValue,
            command.verificationStatus.dbValue,
            command.codeConceptId.value,
            command.category?.dbValue,
            command.criticality?.dbValue,
            command.onsetDate?.let(Date::valueOf),
            command.createdBy?.value,
            command.createdBy?.value,
            command.organizationId.value,
            command.patientId.value,
        ).singleOrNull()
            ?: throw IllegalArgumentException("patient does not exist in the requested organization")

    fun findById(
        tenantScope: TenantScope,
        allergyId: AllergyId,
    ): Allergy? =
        jdbcTemplate.query(
            """
            select $COLUMNS
            from allergies
            where organization_id = ?
              and id = ?
            """.trimIndent(),
            rowMapper,
            tenantScope.organizationId.value,
            allergyId.value,
        ).singleOrNull()

    fun findByPatient(
        tenantScope: TenantScope,
        patientId: PatientId,
    ): List<Allergy> =
        jdbcTemplate.query(
            """
            select $COLUMNS
            from allergies
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
              clinical_status,
              verification_status,
              code_concept_id,
              category,
              criticality,
              onset_date,
              recorded_at,
              version,
              created_at,
              updated_at,
              created_by,
              updated_by
        """

        val rowMapper = RowMapper { rs: ResultSet, _: Int ->
            Allergy(
                id = AllergyId(rs.getObject("id", UUID::class.java)),
                organizationId = OrganizationId(rs.getObject("organization_id", UUID::class.java)),
                patientId = PatientId(rs.getObject("patient_id", UUID::class.java)),
                encounterId = rs.getObject("encounter_id", UUID::class.java)?.let(::EncounterId),
                clinicalStatus = AllergyClinicalStatus.fromDb(rs.getString("clinical_status")),
                verificationStatus = AllergyVerificationStatus.fromDb(rs.getString("verification_status")),
                codeConceptId = CodeableConceptId(rs.getObject("code_concept_id", UUID::class.java)),
                category = rs.getString("category")?.let(AllergyCategory::fromDb),
                criticality = rs.getString("criticality")?.let(AllergyCriticality::fromDb),
                onsetDate = rs.getDate("onset_date")?.toLocalDate(),
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
