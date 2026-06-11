package dev.ehr.identity

import dev.ehr.security.CompartmentEnforcementMode
import dev.ehr.security.EnforcementModeResolver
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

@Component
class OrganizationEnforcementModeResolver(
    private val jdbcTemplate: JdbcTemplate,
) : EnforcementModeResolver {
    override fun resolve(organizationId: OrganizationId): CompartmentEnforcementMode {
        val mode = jdbcTemplate.queryForList(
            "select compartment_enforcement from organizations where id = ?",
            String::class.java,
            organizationId.value,
        ).singleOrNull()
        // The evaluator only asks about the principal's own (existing)
        // organization; a missing row falls back to the default posture.
        return mode?.let(CompartmentEnforcementMode::fromDb) ?: CompartmentEnforcementMode.SHADOW
    }
}
