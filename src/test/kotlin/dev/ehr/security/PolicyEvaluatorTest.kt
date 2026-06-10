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
        assertEquals("policy-spine-v5", decision.policyVersion)
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

    @Test
    fun `allows clinician patient read and write with compatible scopes`() {
        listOf(
            PolicyOperation.READ to "user/Patient.read",
            PolicyOperation.READ to "user/*.read",
            PolicyOperation.WRITE to "user/Patient.write",
            PolicyOperation.WRITE to "system/*.write",
        ).forEach { (operation, scope) ->
            val organizationId = OrganizationId(UUID.randomUUID())
            val principal = principal(
                organizationId = organizationId,
                roles = listOf(MembershipRole.CLINICIAN),
                scopes = scope,
            )

            val decision = evaluator.evaluate(
                principal = principal,
                request = patientRequest(organizationId, operation),
            )

            assertTrue(decision.allowed, "expected $operation with $scope to be allowed")
            assertEquals(listOf(MembershipRole.CLINICIAN), decision.roleBasis)
            assertEquals(listOf(scope), decision.scopeBasis.map { it.rawValue })
            assertEquals(PolicyReasonCode.ALLOWED, decision.reasonCode)
        }
    }

    @Test
    fun `allows staff patient read but denies staff patient write`() {
        val organizationId = OrganizationId(UUID.randomUUID())
        val principal = principal(
            organizationId = organizationId,
            roles = listOf(MembershipRole.STAFF),
            scopes = "user/Patient.read user/Patient.write",
        )

        val readDecision = evaluator.evaluate(
            principal = principal,
            request = patientRequest(organizationId, PolicyOperation.READ),
        )
        assertTrue(readDecision.allowed)
        assertEquals(PolicyReasonCode.ALLOWED, readDecision.reasonCode)

        val writeDecision = evaluator.evaluate(
            principal = principal,
            request = patientRequest(organizationId, PolicyOperation.WRITE),
        )
        assertFalse(writeDecision.allowed)
        assertEquals(listOf(MembershipRole.STAFF), writeDecision.roleBasis)
        assertEquals(PolicyReasonCode.INSUFFICIENT_ROLE, writeDecision.reasonCode)
    }

    @Test
    fun `denies admin roles patient read by default`() {
        listOf(
            MembershipRole.ORG_ADMIN,
            MembershipRole.SYSTEM_ADMIN,
        ).forEach { role ->
            val organizationId = OrganizationId(UUID.randomUUID())
            val principal = principal(
                organizationId = organizationId,
                roles = listOf(role),
                scopes = "user/*.read",
            )

            val decision = evaluator.evaluate(
                principal = principal,
                request = patientRequest(organizationId, PolicyOperation.READ),
            )

            assertFalse(decision.allowed)
            assertEquals(listOf(role), decision.roleBasis)
            assertEquals(PolicyReasonCode.INSUFFICIENT_ROLE, decision.reasonCode)
        }
    }

    @Test
    fun `denies clinician patient write without compatible write scope`() {
        val organizationId = OrganizationId(UUID.randomUUID())
        val principal = principal(
            organizationId = organizationId,
            roles = listOf(MembershipRole.CLINICIAN),
            scopes = "user/*.read",
        )

        val decision = evaluator.evaluate(
            principal = principal,
            request = patientRequest(organizationId, PolicyOperation.WRITE),
        )

        assertFalse(decision.allowed)
        assertEquals(listOf(MembershipRole.CLINICIAN), decision.roleBasis)
        assertEquals(emptyList<SecurityScope>(), decision.scopeBasis)
        assertEquals(PolicyReasonCode.INSUFFICIENT_SCOPE, decision.reasonCode)
    }

    @Test
    fun `denies patient read when request organization does not match principal organization`() {
        val principal = principal(
            organizationId = OrganizationId(UUID.randomUUID()),
            roles = listOf(MembershipRole.CLINICIAN),
            scopes = "user/Patient.read",
        )
        val requestedOrganizationId = OrganizationId(UUID.randomUUID())

        val decision = evaluator.evaluate(
            principal = principal,
            request = patientRequest(requestedOrganizationId, PolicyOperation.READ),
        )

        assertFalse(decision.allowed)
        assertEquals(PolicyReasonCode.ORGANIZATION_MISMATCH, decision.reasonCode)
    }

    @Test
    fun `allows clinician encounter read and write and staff encounter read only`() {
        val organizationId = OrganizationId(UUID.randomUUID())
        val clinician = principal(
            organizationId = organizationId,
            roles = listOf(MembershipRole.CLINICIAN),
            scopes = "user/Encounter.read user/Encounter.write",
        )

        assertTrue(
            evaluator.evaluate(clinician, encounterRequest(organizationId, PolicyOperation.READ)).allowed,
        )
        assertTrue(
            evaluator.evaluate(clinician, encounterRequest(organizationId, PolicyOperation.WRITE)).allowed,
        )

        val staff = principal(
            organizationId = organizationId,
            roles = listOf(MembershipRole.STAFF),
            scopes = "user/Encounter.read user/Encounter.write",
        )

        assertTrue(
            evaluator.evaluate(staff, encounterRequest(organizationId, PolicyOperation.READ)).allowed,
        )
        val staffWrite = evaluator.evaluate(staff, encounterRequest(organizationId, PolicyOperation.WRITE))
        assertFalse(staffWrite.allowed)
        assertEquals(PolicyReasonCode.INSUFFICIENT_ROLE, staffWrite.reasonCode)
    }

    @Test
    fun `denies admin encounter read and scope incompatible encounter access`() {
        val organizationId = OrganizationId(UUID.randomUUID())

        val admin = principal(
            organizationId = organizationId,
            roles = listOf(MembershipRole.ORG_ADMIN),
            scopes = "user/*.read",
        )
        val adminRead = evaluator.evaluate(admin, encounterRequest(organizationId, PolicyOperation.READ))
        assertFalse(adminRead.allowed)
        assertEquals(PolicyReasonCode.INSUFFICIENT_ROLE, adminRead.reasonCode)

        val scopelessClinician = principal(
            organizationId = organizationId,
            roles = listOf(MembershipRole.CLINICIAN),
            scopes = "user/Patient.read",
        )
        val scopelessRead = evaluator.evaluate(scopelessClinician, encounterRequest(organizationId, PolicyOperation.READ))
        assertFalse(scopelessRead.allowed)
        assertEquals(PolicyReasonCode.INSUFFICIENT_SCOPE, scopelessRead.reasonCode)

        val mismatched = evaluator.evaluate(
            principal(
                organizationId = OrganizationId(UUID.randomUUID()),
                roles = listOf(MembershipRole.CLINICIAN),
                scopes = "user/Encounter.read",
            ),
            encounterRequest(organizationId, PolicyOperation.READ),
        )
        assertFalse(mismatched.allowed)
        assertEquals(PolicyReasonCode.ORGANIZATION_MISMATCH, mismatched.reasonCode)
    }

    private fun encounterRequest(
        organizationId: OrganizationId,
        operation: PolicyOperation,
    ): PolicyEvaluationRequest =
        PolicyEvaluationRequest(
            resourceType = PolicyResourceType.ENCOUNTER,
            operation = operation,
            organizationId = organizationId,
        )

    private fun patientRequest(
        organizationId: OrganizationId,
        operation: PolicyOperation,
    ): PolicyEvaluationRequest =
        PolicyEvaluationRequest(
            resourceType = PolicyResourceType.PATIENT,
            operation = operation,
            organizationId = organizationId,
        )

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
