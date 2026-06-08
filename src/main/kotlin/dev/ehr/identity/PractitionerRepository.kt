package dev.ehr.identity

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.util.UUID

@Repository
class PractitionerRepository(
    private val jdbcTemplate: JdbcTemplate,
) {
    fun create(
        userId: UserId,
        displayName: String,
        npi: String? = null,
        status: PractitionerStatus = PractitionerStatus.ACTIVE,
    ): Practitioner =
        jdbcTemplate.queryForObject(
            """
            insert into practitioners (user_id, npi, display_name, status)
            values (?, ?, ?, ?)
            returning id, user_id, npi, display_name, status, created_at, updated_at
            """.trimIndent(),
            rowMapper,
            userId.value,
            npi,
            displayName,
            status.dbValue,
        )!!

    fun findById(id: PractitionerId): Practitioner? =
        jdbcTemplate.query(
            """
            select id, user_id, npi, display_name, status, created_at, updated_at
            from practitioners
            where id = ?
            """.trimIndent(),
            rowMapper,
            id.value,
        ).singleOrNull()

    fun findByUserId(userId: UserId): Practitioner? =
        jdbcTemplate.query(
            """
            select id, user_id, npi, display_name, status, created_at, updated_at
            from practitioners
            where user_id = ?
            """.trimIndent(),
            rowMapper,
            userId.value,
        ).singleOrNull()

    private companion object {
        val rowMapper = RowMapper { rs: ResultSet, _: Int ->
            Practitioner(
                id = PractitionerId(rs.getObject("id", UUID::class.java)),
                userId = UserId(rs.getObject("user_id", UUID::class.java)),
                npi = rs.getString("npi"),
                displayName = rs.getString("display_name"),
                status = PractitionerStatus.fromDb(rs.getString("status")),
                createdAt = rs.getTimestamp("created_at").toInstant(),
                updatedAt = rs.getTimestamp("updated_at").toInstant(),
            )
        }
    }
}
