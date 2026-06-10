package dev.ehr.note

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
import java.util.UUID

@Repository
class ClinicalNoteRepository(
    private val jdbcTemplate: JdbcTemplate,
) {
    fun create(command: ClinicalNoteCreateCommand): ClinicalNote =
        jdbcTemplate.query(
            """
            insert into clinical_notes (
              organization_id,
              patient_id,
              encounter_id,
              type_concept_id,
              title,
              content_text,
              created_by,
              updated_by
            )
            select
              e.organization_id,
              e.patient_id,
              e.id,
              ?,
              ?,
              ?,
              ?,
              ?
            from encounters e
            where e.organization_id = ?
              and e.id = ?
              and e.patient_id = ?
            returning $COLUMNS
            """.trimIndent(),
            rowMapper,
            command.typeConceptId.value,
            command.title,
            command.contentText,
            command.createdBy?.value,
            command.createdBy?.value,
            command.organizationId.value,
            command.encounterId.value,
            command.patientId.value,
        ).singleOrNull()
            ?: throw IllegalArgumentException("encounter does not exist in the requested organization")

    fun findById(
        tenantScope: TenantScope,
        noteId: ClinicalNoteId,
    ): ClinicalNote? =
        jdbcTemplate.query(
            """
            select $COLUMNS
            from clinical_notes
            where organization_id = ?
              and id = ?
            """.trimIndent(),
            rowMapper,
            tenantScope.organizationId.value,
            noteId.value,
        ).singleOrNull()

    fun findByPatient(
        tenantScope: TenantScope,
        patientId: PatientId,
    ): List<ClinicalNote> =
        jdbcTemplate.query(
            """
            select $COLUMNS
            from clinical_notes
            where organization_id = ?
              and patient_id = ?
            order by authored_at desc, id
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
              type_concept_id,
              title,
              content_text,
              authored_at,
              version,
              created_at,
              updated_at,
              created_by,
              updated_by
        """

        val rowMapper = RowMapper { rs: ResultSet, _: Int ->
            ClinicalNote(
                id = ClinicalNoteId(rs.getObject("id", UUID::class.java)),
                organizationId = OrganizationId(rs.getObject("organization_id", UUID::class.java)),
                patientId = PatientId(rs.getObject("patient_id", UUID::class.java)),
                encounterId = EncounterId(rs.getObject("encounter_id", UUID::class.java)),
                status = ClinicalNoteStatus.fromDb(rs.getString("status")),
                typeConceptId = CodeableConceptId(rs.getObject("type_concept_id", UUID::class.java)),
                title = rs.getString("title"),
                contentText = rs.getString("content_text"),
                authoredAt = rs.getTimestamp("authored_at").toInstant(),
                version = rs.getInt("version"),
                createdAt = rs.getTimestamp("created_at").toInstant(),
                updatedAt = rs.getTimestamp("updated_at").toInstant(),
                createdBy = rs.getObject("created_by", UUID::class.java)?.let(::UserId),
                updatedBy = rs.getObject("updated_by", UUID::class.java)?.let(::UserId),
            )
        }
    }
}
