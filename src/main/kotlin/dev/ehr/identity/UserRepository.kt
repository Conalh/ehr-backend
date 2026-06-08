package dev.ehr.identity

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.util.UUID

@Repository
class UserRepository(
    private val jdbcTemplate: JdbcTemplate,
) {
    fun create(
        externalSubject: String,
        email: String,
        displayName: String,
        status: UserStatus = UserStatus.ACTIVE,
    ): User =
        jdbcTemplate.queryForObject(
            """
            insert into users (external_subject, email, display_name, status)
            values (?, ?, ?, ?)
            returning id, external_subject, email, display_name, status, created_at, updated_at
            """.trimIndent(),
            rowMapper,
            externalSubject,
            email,
            displayName,
            status.dbValue,
        )!!

    fun findById(id: UserId): User? =
        jdbcTemplate.query(
            """
            select id, external_subject, email, display_name, status, created_at, updated_at
            from users
            where id = ?
            """.trimIndent(),
            rowMapper,
            id.value,
        ).singleOrNull()

    fun findByExternalSubject(externalSubject: String): User? =
        jdbcTemplate.query(
            """
            select id, external_subject, email, display_name, status, created_at, updated_at
            from users
            where external_subject = ?
            """.trimIndent(),
            rowMapper,
            externalSubject,
        ).singleOrNull()

    private companion object {
        val rowMapper = RowMapper { rs: ResultSet, _: Int ->
            User(
                id = UserId(rs.getObject("id", UUID::class.java)),
                externalSubject = rs.getString("external_subject"),
                email = rs.getString("email"),
                displayName = rs.getString("display_name"),
                status = UserStatus.fromDb(rs.getString("status")),
                createdAt = rs.getTimestamp("created_at").toInstant(),
                updatedAt = rs.getTimestamp("updated_at").toInstant(),
            )
        }
    }
}
