package dev.ehr.security

import dev.ehr.identity.MembershipId
import dev.ehr.identity.MembershipRole
import dev.ehr.identity.OrganizationId
import dev.ehr.identity.UserId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class PolicyModelsTest {
    @Test
    fun `policy decision carries authorization evidence fields`() {
        val userId = UserId(UUID.randomUUID())
        val organizationId = OrganizationId(UUID.randomUUID())
        val membershipId = MembershipId(UUID.randomUUID())
        val scopes = SecurityScope.parse("user/*.read")

        val decision = PolicyDecision(
            allowed = true,
            subjectUserId = userId,
            organizationId = organizationId,
            membershipId = membershipId,
            resourceType = PolicyResourceType.ORGANIZATION,
            operation = PolicyOperation.READ,
            roleBasis = listOf(MembershipRole.ORG_ADMIN),
            scopeBasis = scopes,
            relationshipBasis = RelationshipBasis.CARE_TEAM_MEMBER,
            purposeOfUse = "deferred",
            policyVersion = "policy-spine-v1",
            reasonCode = PolicyReasonCode.ALLOWED,
        )

        assertTrue(decision.allowed)
        assertEquals(userId, decision.subjectUserId)
        assertEquals(organizationId, decision.organizationId)
        assertEquals(membershipId, decision.membershipId)
        assertEquals(PolicyResourceType.ORGANIZATION, decision.resourceType)
        assertEquals(PolicyOperation.READ, decision.operation)
        assertEquals(listOf(MembershipRole.ORG_ADMIN), decision.roleBasis)
        assertEquals(scopes, decision.scopeBasis)
        assertEquals(RelationshipBasis.CARE_TEAM_MEMBER, decision.relationshipBasis)
        assertEquals("deferred", decision.purposeOfUse)
        assertEquals("policy-spine-v1", decision.policyVersion)
        assertEquals(PolicyReasonCode.ALLOWED, decision.reasonCode)
    }

    @Test
    fun `policy decision can represent deny by default with deferred fields empty`() {
        val decision = PolicyDecision(
            allowed = false,
            subjectUserId = null,
            organizationId = OrganizationId(UUID.randomUUID()),
            membershipId = MembershipId(UUID.randomUUID()),
            resourceType = PolicyResourceType.SYSTEM,
            operation = PolicyOperation.WRITE,
            roleBasis = emptyList(),
            scopeBasis = emptyList(),
            relationshipBasis = null,
            purposeOfUse = null,
            policyVersion = "policy-spine-v1",
            reasonCode = PolicyReasonCode.UNSUPPORTED_RESOURCE,
        )

        assertFalse(decision.allowed)
        assertEquals(null, decision.subjectUserId)
        assertEquals(emptyList<MembershipRole>(), decision.roleBasis)
        assertEquals(emptyList<SecurityScope>(), decision.scopeBasis)
        assertEquals(null, decision.relationshipBasis)
        assertEquals(null, decision.purposeOfUse)
        assertEquals(PolicyReasonCode.UNSUPPORTED_RESOURCE, decision.reasonCode)
    }
}
