package dev.ehr.terminology

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.util.UUID

@Repository
class CodeSystemRepository(
    private val jdbcTemplate: JdbcTemplate,
) {
    fun create(
        canonicalUri: String,
        name: String,
        publisher: String? = null,
        licenseNote: String? = null,
    ): CanonicalCodeSystem =
        jdbcTemplate.queryForObject(
            """
            insert into code_systems (
              canonical_uri,
              name,
              publisher,
              license_note
            )
            values (?, ?, ?, ?)
            returning id, canonical_uri, name, publisher, license_note, created_at, updated_at
            """.trimIndent(),
            rowMapper,
            canonicalUri,
            name,
            publisher,
            licenseNote,
        )!!

    fun findById(id: CodeSystemId): CanonicalCodeSystem? =
        jdbcTemplate.query(
            """
            select id, canonical_uri, name, publisher, license_note, created_at, updated_at
            from code_systems
            where id = ?
            """.trimIndent(),
            rowMapper,
            id.value,
        ).singleOrNull()

    fun findByCanonicalUri(canonicalUri: String): CanonicalCodeSystem? =
        jdbcTemplate.query(
            """
            select id, canonical_uri, name, publisher, license_note, created_at, updated_at
            from code_systems
            where canonical_uri = ?
            """.trimIndent(),
            rowMapper,
            canonicalUri,
        ).singleOrNull()

    private companion object {
        val rowMapper = RowMapper { rs: ResultSet, _: Int ->
            CanonicalCodeSystem(
                id = CodeSystemId(rs.getObject("id", UUID::class.java)),
                canonicalUri = rs.getString("canonical_uri"),
                name = rs.getString("name"),
                publisher = rs.getString("publisher"),
                licenseNote = rs.getString("license_note"),
                createdAt = rs.getTimestamp("created_at").toInstant(),
                updatedAt = rs.getTimestamp("updated_at").toInstant(),
            )
        }
    }
}
