package dev.ehr.terminology

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.util.UUID

@Repository
class CodingRepository(
    private val jdbcTemplate: JdbcTemplate,
) {
    fun create(
        system: String,
        code: String,
        display: String? = null,
        version: String? = null,
        userSelected: Boolean = false,
        codeSystemVersionId: CodeSystemVersionId? = null,
    ): Coding =
        jdbcTemplate.queryForObject(
            """
            insert into codings (
              code_system_version_id,
              system,
              version,
              code,
              display,
              user_selected
            )
            values (?, ?, ?, ?, ?, ?)
            returning id, code_system_version_id, system, version, code, display, user_selected, created_at
            """.trimIndent(),
            rowMapper,
            codeSystemVersionId?.value,
            system,
            version,
            code,
            display,
            userSelected,
        )!!

    fun findById(id: CodingId): Coding? =
        jdbcTemplate.query(
            """
            select id, code_system_version_id, system, version, code, display, user_selected, created_at
            from codings
            where id = ?
            """.trimIndent(),
            rowMapper,
            id.value,
        ).singleOrNull()

    fun findBySystemCodeVersion(
        system: String,
        code: String,
        version: String? = null,
    ): Coding? =
        jdbcTemplate.query(
            """
            select id, code_system_version_id, system, version, code, display, user_selected, created_at
            from codings
            where system = ?
              and code = ?
              and coalesce(version, '') = coalesce(?, '')
            """.trimIndent(),
            rowMapper,
            system,
            code,
            version,
        ).singleOrNull()

    companion object {
        val rowMapper = RowMapper { rs: ResultSet, _: Int ->
            Coding(
                id = CodingId(rs.getObject("id", UUID::class.java)),
                codeSystemVersionId = rs.getObject("code_system_version_id", UUID::class.java)
                    ?.let(::CodeSystemVersionId),
                system = rs.getString("system"),
                version = rs.getString("version"),
                code = rs.getString("code"),
                display = rs.getString("display"),
                userSelected = rs.getBoolean("user_selected"),
                createdAt = rs.getTimestamp("created_at").toInstant(),
            )
        }
    }
}
