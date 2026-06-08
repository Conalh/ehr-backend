package dev.ehr.terminology

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID

@Repository
class CodeableConceptRepository(
    private val jdbcTemplate: JdbcTemplate,
) {
    @Transactional
    fun create(
        text: String? = null,
        bindingContext: BindingContext? = null,
        codingIds: List<CodingId>,
        primaryCodingId: CodingId?,
    ): CodeableConcept {
        require(codingIds.isNotEmpty()) { "codeable concept requires at least one coding" }
        require(codingIds.distinct().size == codingIds.size) { "codeable concept coding IDs must be distinct" }
        require(primaryCodingId != null) { "primary coding is required" }
        require(primaryCodingId in codingIds) { "primary coding must be present in the ordered coding list" }

        val row = jdbcTemplate.queryForObject(
            """
            insert into codeable_concepts (
              text,
              primary_coding_id,
              binding_context
            )
            values (?, ?, ?)
            returning id, text, primary_coding_id, binding_context, created_at
            """.trimIndent(),
            ::mapConceptHeader,
            text,
            primaryCodingId.value,
            bindingContext?.value,
        )!!

        codingIds.forEachIndexed { index, codingId ->
            jdbcTemplate.update(
                """
                insert into codeable_concept_codings (
                  codeable_concept_id,
                  coding_id,
                  ordinal
                )
                values (?, ?, ?)
                """.trimIndent(),
                row.id.value,
                codingId.value,
                index,
            )
        }

        return findById(row.id)!!
    }

    fun findById(id: CodeableConceptId): CodeableConcept? {
        val row = jdbcTemplate.query(
            """
            select id, text, primary_coding_id, binding_context, created_at
            from codeable_concepts
            where id = ?
            """.trimIndent(),
            ::mapConceptHeader,
            id.value,
        ).singleOrNull() ?: return null

        val codings = jdbcTemplate.query(
            """
            select
              c.id,
              c.code_system_version_id,
              c.system,
              c.version,
              c.code,
              c.display,
              c.user_selected,
              c.created_at
            from codeable_concept_codings ccc
            join codings c on c.id = ccc.coding_id
            where ccc.codeable_concept_id = ?
            order by ccc.ordinal
            """.trimIndent(),
            CodingRepository.rowMapper,
            id.value,
        )
        val primaryCoding = codings.single { it.id == row.primaryCodingId }

        return CodeableConcept(
            id = row.id,
            text = row.text,
            bindingContext = row.bindingContext,
            primaryCoding = primaryCoding,
            codings = codings,
            createdAt = row.createdAt,
        )
    }

    private fun mapConceptHeader(rs: ResultSet, @Suppress("UNUSED_PARAMETER") rowNumber: Int): CodeableConceptHeader =
        CodeableConceptHeader(
            id = CodeableConceptId(rs.getObject("id", UUID::class.java)),
            text = rs.getString("text"),
            primaryCodingId = CodingId(rs.getObject("primary_coding_id", UUID::class.java)),
            bindingContext = rs.getString("binding_context")?.let(::BindingContext),
            createdAt = rs.getTimestamp("created_at").toInstant(),
        )

    private data class CodeableConceptHeader(
        val id: CodeableConceptId,
        val text: String?,
        val primaryCodingId: CodingId,
        val bindingContext: BindingContext?,
        val createdAt: Instant,
    )
}
