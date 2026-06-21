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

    /**
     * Tenant-honest lookup: a practitioner is visible to an organization only
     * when its user holds an active membership there.
     */
    fun findByIdInOrganization(
        tenantScope: TenantScope,
        id: PractitionerId,
    ): Practitioner? =
        jdbcTemplate.query(
            """
            select p.id, p.user_id, p.npi, p.display_name, p.status, p.created_at, p.updated_at
            from practitioners p
            join memberships m on m.user_id = p.user_id
            where p.id = ?
              and m.organization_id = ?
              and m.status = 'active'
            """.trimIndent(),
            rowMapper,
            id.value,
            tenantScope.organizationId.value,
        ).singleOrNull()

    fun findByOrganization(tenantScope: TenantScope): List<Practitioner> =
        jdbcTemplate.query(
            """
            select distinct p.id, p.user_id, p.npi, p.display_name, p.status, p.created_at, p.updated_at
            from practitioners p
            join memberships m on m.user_id = p.user_id
            where m.organization_id = ?
              and m.status = 'active'
            order by p.created_at, p.id
            """.trimIndent(),
            rowMapper,
            tenantScope.organizationId.value,
        )

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
