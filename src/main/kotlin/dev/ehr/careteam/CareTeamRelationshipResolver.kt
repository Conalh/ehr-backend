package dev.ehr.careteam

import dev.ehr.identity.OrganizationId
import dev.ehr.identity.UserId
import dev.ehr.security.RelationshipBasis
import dev.ehr.security.RelationshipResolver
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class CareTeamRelationshipResolver(
    private val jdbcTemplate: JdbcTemplate,
) : RelationshipResolver {
    override fun resolve(
        organizationId: OrganizationId,
        userId: UserId,
        patientId: UUID,
    ): RelationshipBasis? {
        val origins = jdbcTemplate.queryForList(
            """
            select distinct origin
            from care_team_memberships
            where organization_id = ?
              and user_id = ?
              and patient_id = ?
              and period_end is null
            """.trimIndent(),
            String::class.java,
            organizationId.value,
            userId.value,
            patientId,
        )
        return when {
            CareTeamMembershipOrigin.EXPLICIT.dbValue in origins -> RelationshipBasis.CARE_TEAM_MEMBER
            origins.isNotEmpty() -> RelationshipBasis.ENCOUNTER_DERIVED
            else -> null
        }
    }
}
