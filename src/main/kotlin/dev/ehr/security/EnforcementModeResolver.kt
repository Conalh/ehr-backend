package dev.ehr.security

import dev.ehr.identity.OrganizationId

/**
 * Resolves an organization's compartment-enforcement posture. Kept narrow so
 * the policy evaluator stays cheap to test.
 */
fun interface EnforcementModeResolver {
    fun resolve(organizationId: OrganizationId): CompartmentEnforcementMode
}
