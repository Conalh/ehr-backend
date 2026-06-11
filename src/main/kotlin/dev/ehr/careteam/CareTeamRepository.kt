package dev.ehr.careteam

import dev.ehr.identity.OrganizationId
import dev.ehr.identity.TenantScope
import dev.ehr.identity.UserId
import dev.ehr.patient.PatientId
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.util.UUID

@Repository
class CareTeamRepository(
    private val jdbcTemplate: JdbcTemplate,
) {
    fun addMember(
        organizationId: OrganizationId,
        patientId: PatientId,
        userId: UserId,
        role: CareTeamRole,
        origin: CareTeamMembershipOrigin,
        createdBy: UserId?,
    ): CareTeamMembership =
        jdbcTemplate.query(
            """
            insert into care_team_memberships (
              organization_id, patient_id, user_id, role, origin, created_by
            )
            select p.organization_id, p.id, ?, ?, ?, ?
            from patients p
            where p.organization_id = ?
              and p.id = ?
            returning $COLUMNS
            """.trimIndent(),
            rowMapper,
            userId.value,
            role.dbValue,
            origin.dbValue,
            createdBy?.value,
            organizationId.value,
            patientId.value,
        ).singleOrNull()
            ?: throw IllegalArgumentException("patient does not exist in the requested organization")

    /** Idempotent: returns the existing active membership when one already exists. */
    fun ensureMembership(
        organizationId: OrganizationId,
        patientId: PatientId,
        userId: UserId,
        role: CareTeamRole,
        origin: CareTeamMembershipOrigin,
    ): CareTeamMembership {
        val existing = jdbcTemplate.query(
            """
            select $COLUMNS
            from care_team_memberships
            where organization_id = ?
              and patient_id = ?
              and user_id = ?
              and role = ?
              and period_end is null
            """.trimIndent(),
            rowMapper,
            organizationId.value,
            patientId.value,
            userId.value,
            role.dbValue,
        ).singleOrNull()
        if (existing != null) {
            return existing
        }
        return addMember(organizationId, patientId, userId, role, origin, createdBy = userId)
    }

    fun findById(
        tenantScope: TenantScope,
        membershipId: CareTeamMembershipId,
    ): CareTeamMembership? =
        jdbcTemplate.query(
            """
            select $COLUMNS
            from care_team_memberships
            where organization_id = ?
              and id = ?
            """.trimIndent(),
            rowMapper,
            tenantScope.organizationId.value,
            membershipId.value,
        ).singleOrNull()

    fun findActiveByPatient(
        tenantScope: TenantScope,
        patientId: PatientId,
    ): List<CareTeamMembership> =
        jdbcTemplate.query(
            """
            select $COLUMNS
            from care_team_memberships
            where organization_id = ?
              and patient_id = ?
              and period_end is null
            order by period_start, id
            """.trimIndent(),
            rowMapper,
            tenantScope.organizationId.value,
            patientId.value,
        )

    fun end(
        tenantScope: TenantScope,
        membershipId: CareTeamMembershipId,
    ): CareTeamMembership? =
        jdbcTemplate.query(
            """
            update care_team_memberships
            set period_end = now()
            where organization_id = ?
              and id = ?
              and period_end is null
            returning $COLUMNS
            """.trimIndent(),
            rowMapper,
            tenantScope.organizationId.value,
            membershipId.value,
        ).singleOrNull()

    private companion object {
        const val COLUMNS = """
              id, organization_id, patient_id, user_id, role, origin,
              period_start, period_end, created_at, created_by
        """

        val rowMapper = RowMapper { rs: ResultSet, _: Int ->
            CareTeamMembership(
                id = CareTeamMembershipId(rs.getObject("id", UUID::class.java)),
                organizationId = OrganizationId(rs.getObject("organization_id", UUID::class.java)),
                patientId = PatientId(rs.getObject("patient_id", UUID::class.java)),
                userId = UserId(rs.getObject("user_id", UUID::class.java)),
                role = CareTeamRole.fromDb(rs.getString("role")),
                origin = CareTeamMembershipOrigin.fromDb(rs.getString("origin")),
                periodStart = rs.getTimestamp("period_start").toInstant(),
                periodEnd = rs.getTimestamp("period_end")?.toInstant(),
                createdAt = rs.getTimestamp("created_at").toInstant(),
                createdBy = rs.getObject("created_by", UUID::class.java)?.let(::UserId),
            )
        }
    }
}
