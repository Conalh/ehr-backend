package dev.ehr.security

import dev.ehr.identity.MembershipId
import dev.ehr.identity.MembershipRole
import dev.ehr.identity.OAuthClientId
import dev.ehr.identity.OrganizationId
import dev.ehr.identity.UserId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class PolicyEvaluatorTest {
    private val evaluator = PolicyEvaluator()

    @Test
    fun `allows org admin organization read with compatible user scope`() {
        val organizationId = OrganizationId(UUID.randomUUID())
        val principal = principal(
            organizationId = organizationId,
            roles = listOf(MembershipRole.ORG_ADMIN),
            scopes = "user/*.read",
        )

        val decision = evaluator.evaluate(
            principal = principal,
            request = PolicyEvaluationRequest(
                resourceType = PolicyResourceType.ORGANIZATION,
                operation = PolicyOperation.READ,
                organizationId = organizationId,
            ),
        )

        assertTrue(decision.allowed)
        assertEquals(principal.subject.userId, decision.subjectUserId)
        assertEquals(organizationId, decision.organizationId)
        assertEquals(principal.membership.membershipId, decision.membershipId)
        assertEquals(listOf(MembershipRole.ORG_ADMIN), decision.roleBasis)
        assertEquals(listOf("user/*.read"), decision.scopeBasis.map { it.rawValue })
        assertEquals(null, decision.relationshipBasis)
        assertEquals(null, decision.purposeOfUse)
        assertEquals("policy-spine-v1", decision.policyVersion)
        assertEquals(PolicyReasonCode.ALLOWED, decision.reasonCode)
    }

    @Test
    fun `allows system admin organization read with compatible system scope`() {
        val organizationId = OrganizationId(UUID.randomUUID())
        val principal = principal(
            organizationId = organizationId,
            roles = listOf(MembershipRole.SYSTEM_ADMIN),
            scopes = "system/*.read",
        )

        val decision = evaluator.evaluate(
            principal = principal,
            request = organizationReadRequest(organizationId),
        )

        assertTrue(decision.allowed)
        assertEquals(listOf(MembershipRole.SYSTEM_ADMIN), decision.roleBasis)
        assertEquals(listOf("system/*.read"), decision.scopeBasis.map { it.rawValue })
        assertEquals(PolicyReasonCode.ALLOWED, decision.reasonCode)
    }

    @Test
    fun `denies organization read when compatible scope is missing`() {
        val organizationId = OrganizationId(UUID.randomUUID())
        val principal = principal(
            organizationId = organizationId,
            roles = listOf(MembershipRole.ORG_ADMIN),
            scopes = "patient/Patient.rs",
        )

        val decision = evaluator.evaluate(
            principal = principal,
            request = organizationReadRequest(organizationId),
        )

        assertFalse(decision.allowed)
        assertEquals(listOf(MembershipRole.ORG_ADMIN), decision.roleBasis)
        assertEquals(emptyList<SecurityScope>(), decision.scopeBasis)
        assertEquals(PolicyReasonCode.INSUFFICIENT_SCOPE, decision.reasonCode)
    }

    @Test
    fun `denies non admin roles for organization read`() {
        listOf(
            MembershipRole.STAFF,
            MembershipRole.CLINICIAN,
            MembershipRole.PATIENT,
        ).forEach { role ->
            val organizationId = OrganizationId(UUID.randomUUID())
            val principal = principal(
                organizationId = organizationId,
                roles = listOf(role),
                scopes = "user/*.read",
            )

            val decision = evaluator.evaluate(
                principal = principal,
                request = organizationReadRequest(organizationId),
            )

            assertFalse(decision.allowed)
            assertEquals(listOf(role), decision.roleBasis)
            assertEquals(listOf("user/*.read"), decision.scopeBasis.map { it.rawValue })
            assertEquals(PolicyReasonCode.INSUFFICIENT_ROLE, decision.reasonCode)
        }
    }

    @Test
    fun `denies organization read when request organization does not match principal organization`() {
        val principal = principal(
            organizationId = OrganizationId(UUID.randomUUID()),
            roles = listOf(MembershipRole.ORG_ADMIN),
            scopes = "user/*.read",
        )
        val requestedOrganizationId = OrganizationId(UUID.randomUUID())

        val decision = evaluator.evaluate(
            principal = principal,
            request = organizationReadRequest(requestedOrganizationId),
        )

        assertFalse(decision.allowed)
        assertEquals(requestedOrganizationId, decision.organizationId)
        assertEquals(emptyList<MembershipRole>(), decision.roleBasis)
        assertEquals(emptyList<SecurityScope>(), decision.scopeBasis)
        assertEquals(PolicyReasonCode.ORGANIZATION_MISMATCH, decision.reasonCode)
    }

    @Test
    fun `denies unsupported resource type`() {
        val organizationId = OrganizationId(UUID.randomUUID())
        val principal = principal(
            organizationId = organizationId,
            roles = listOf(MembershipRole.ORG_ADMIN),
            scopes = "user/*.read",
        )

        val decision = evaluator.evaluate(
            principal = principal,
            request = PolicyEvaluationRequest(
                resourceType = PolicyResourceType.SYSTEM,
                operation = PolicyOperation.READ,
                organizationId = organizationId,
            ),
        )

        assertFalse(decision.allowed)
        assertEquals(PolicyReasonCode.UNSUPPORTED_RESOURCE, decision.reasonCode)
    }

    @Test
    fun `denies unsupported operation`() {
        val organizationId = OrganizationId(UUID.randomUUID())
        val principal = principal(
            organizationId = organizationId,
            roles = listOf(MembershipRole.ORG_ADMIN),
            scopes = "user/*.read",
        )

        val decision = evaluator.evaluate(
            principal = principal,
            request = PolicyEvaluationRequest(
                resourceType = PolicyResourceType.ORGANIZATION,
                operation = PolicyOperation.WRITE,
                organizationId = organizationId,
            ),
        )

        assertFalse(decision.allowed)
        assertEquals(PolicyReasonCode.UNSUPPORTED_OPERATION, decision.reasonCode)
    }

    private fun organizationReadRequest(organizationId: OrganizationId): PolicyEvaluationRequest =
        PolicyEvaluationRequest(
            resourceType = PolicyResourceType.ORGANIZATION,
            operation = PolicyOperation.READ,
            organizationId = organizationId,
        )

    private fun principal(
        organizationId: OrganizationId,
        roles: List<MembershipRole>,
        scopes: String,
    ): SecurityPrincipal =
        SecurityPrincipal(
            subject = AuthenticatedSubject(
                externalSubject = "subject-${UUID.randomUUID()}",
                userId = UserId(UUID.randomUUID()),
                clientId = OAuthClientId(UUID.randomUUID()),
                scopes = SecurityScope.parse(scopes),
            ),
            organization = OrganizationContext(organizationId),
            membership = MembershipContext(
                membershipId = MembershipId(UUID.randomUUID()),
                roles = roles,
            ),
        )
}
