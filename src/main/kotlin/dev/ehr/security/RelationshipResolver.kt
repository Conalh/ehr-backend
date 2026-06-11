package dev.ehr.security

import dev.ehr.identity.OrganizationId
import dev.ehr.identity.UserId
import java.util.UUID

/**
 * Resolves whether a user has a treatment relationship with a patient.
 * Implemented against care-team memberships; kept narrow so the policy
 * evaluator stays cheap to test.
 */
fun interface RelationshipResolver {
    fun resolve(
        organizationId: OrganizationId,
        userId: UserId,
        patientId: UUID,
    ): RelationshipBasis?
}
