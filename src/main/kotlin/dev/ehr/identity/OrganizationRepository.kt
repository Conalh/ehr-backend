package dev.ehr.identity

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.util.UUID

@Repository
class OrganizationRepository(
    private val jdbcTemplate: JdbcTemplate,
) {
    fun create(
        slug: String,
        displayName: String,
        status: OrganizationStatus = OrganizationStatus.ACTIVE,
    ): Organization =
        jdbcTemplate.queryForObject(
            """
            insert into organizations (slug, display_name, status)
            values (?, ?, ?)
            returning id, slug, display_name, status, created_at, updated_at
            """.trimIndent(),
            rowMapper,
            slug,
            displayName,
            status.dbValue,
        )!!

    fun findById(id: OrganizationId): Organization? =
        jdbcTemplate.query(
            """
            select id, slug, display_name, status, created_at, updated_at
            from organizations
            where id = ?
            """.trimIndent(),
            rowMapper,
            id.value,
        ).singleOrNull()

    fun findBySlug(slug: String): Organization? =
        jdbcTemplate.query(
            """
            select id, slug, display_name, status, created_at, updated_at
            from organizations
            where slug = ?
            """.trimIndent(),
            rowMapper,
            slug,
        ).singleOrNull()

    private companion object {
        val rowMapper = RowMapper { rs: ResultSet, _: Int ->
            Organization(
                id = OrganizationId(rs.getObject("id", UUID::class.java)),
                slug = rs.getString("slug"),
                displayName = rs.getString("display_name"),
                status = OrganizationStatus.fromDb(rs.getString("status")),
                createdAt = rs.getTimestamp("created_at").toInstant(),
                updatedAt = rs.getTimestamp("updated_at").toInstant(),
            )
        }
    }
}
