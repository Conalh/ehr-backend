package dev.ehr.identity

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.util.UUID

@Repository
class MembershipRepository(
    private val jdbcTemplate: JdbcTemplate,
) {
    fun create(
        organizationId: OrganizationId,
        userId: UserId,
        practitionerId: PractitionerId? = null,
        status: MembershipStatus = MembershipStatus.ACTIVE,
    ): Membership =
        jdbcTemplate.queryForObject(
            """
            insert into memberships (organization_id, user_id, practitioner_id, status)
            values (?, ?, ?, ?)
            returning id, organization_id, user_id, practitioner_id, status, created_at, updated_at
            """.trimIndent(),
            rowMapper,
            organizationId.value,
            userId.value,
            practitionerId?.value,
            status.dbValue,
        )!!

    fun findById(id: MembershipId): Membership? =
        jdbcTemplate.query(
            """
            select id, organization_id, user_id, practitioner_id, status, created_at, updated_at
            from memberships
            where id = ?
            """.trimIndent(),
            rowMapper,
            id.value,
        ).singleOrNull()

    fun findById(
        tenantScope: TenantScope,
        id: MembershipId,
    ): Membership? =
        jdbcTemplate.query(
            """
            select id, organization_id, user_id, practitioner_id, status, created_at, updated_at
            from memberships
            where organization_id = ?
              and id = ?
            """.trimIndent(),
            rowMapper,
            tenantScope.organizationId.value,
            id.value,
        ).singleOrNull()

    fun findByOrganizationAndUser(
        organizationId: OrganizationId,
        userId: UserId,
    ): Membership? =
        jdbcTemplate.query(
            """
            select id, organization_id, user_id, practitioner_id, status, created_at, updated_at
            from memberships
            where organization_id = ?
              and user_id = ?
            """.trimIndent(),
            rowMapper,
            organizationId.value,
            userId.value,
        ).singleOrNull()

    fun findActiveByOrganizationAndUser(
        organizationId: OrganizationId,
        userId: UserId,
    ): Membership? =
        jdbcTemplate.query(
            """
            select id, organization_id, user_id, practitioner_id, status, created_at, updated_at
            from memberships
            where organization_id = ?
              and user_id = ?
              and status = ?
            """.trimIndent(),
            rowMapper,
            organizationId.value,
            userId.value,
            MembershipStatus.ACTIVE.dbValue,
        ).singleOrNull()

    fun findActiveByUser(userId: UserId): List<Membership> =
        jdbcTemplate.query(
            """
            select id, organization_id, user_id, practitioner_id, status, created_at, updated_at
            from memberships
            where user_id = ?
              and status = ?
            order by created_at, id
            """.trimIndent(),
            rowMapper,
            userId.value,
            MembershipStatus.ACTIVE.dbValue,
        )

    fun findByOrganizationId(organizationId: OrganizationId): List<Membership> =
        jdbcTemplate.query(
            """
            select id, organization_id, user_id, practitioner_id, status, created_at, updated_at
            from memberships
            where organization_id = ?
            order by created_at, id
            """.trimIndent(),
            rowMapper,
            organizationId.value,
        )

    fun addRole(membershipId: MembershipId, role: MembershipRole) {
        jdbcTemplate.update(
            "insert into membership_roles (membership_id, role) values (?, ?)",
            membershipId.value,
            role.dbValue,
        )
    }

    fun findRoles(membershipId: MembershipId): List<MembershipRole> =
        jdbcTemplate.queryForList(
            """
            select role
            from membership_roles
            where membership_id = ?
            order by role
            """.trimIndent(),
            String::class.java,
            membershipId.value,
        ).map(MembershipRole::fromDb)

    fun findRoles(
        tenantScope: TenantScope,
        membershipId: MembershipId,
    ): List<MembershipRole> =
        jdbcTemplate.queryForList(
            """
            select mr.role
            from membership_roles mr
            join memberships m on m.id = mr.membership_id
            where m.organization_id = ?
              and mr.membership_id = ?
            order by mr.role
            """.trimIndent(),
            String::class.java,
            tenantScope.organizationId.value,
            membershipId.value,
        ).map(MembershipRole::fromDb)

    private companion object {
        val rowMapper = RowMapper { rs: ResultSet, _: Int ->
            Membership(
                id = MembershipId(rs.getObject("id", UUID::class.java)),
                organizationId = OrganizationId(rs.getObject("organization_id", UUID::class.java)),
                userId = UserId(rs.getObject("user_id", UUID::class.java)),
                practitionerId = rs.getObject("practitioner_id", UUID::class.java)?.let(::PractitionerId),
                status = MembershipStatus.fromDb(rs.getString("status")),
                createdAt = rs.getTimestamp("created_at").toInstant(),
                updatedAt = rs.getTimestamp("updated_at").toInstant(),
            )
        }
    }
}
