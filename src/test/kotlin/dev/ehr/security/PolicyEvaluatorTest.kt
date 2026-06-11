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
    private val evaluator = evaluator()

    /** Fails the test if the evaluator consults the resolver at all. */
    private val untouchableResolver = RelationshipResolver { _, _, _ ->
        throw AssertionError("relationship resolver must not be consulted")
    }

    private fun evaluator(
        relationshipResolver: RelationshipResolver = RelationshipResolver { _, _, _ -> null },
        mode: CompartmentEnforcementMode = CompartmentEnforcementMode.SHADOW,
        breakGlassReason: String? = null,
    ) = PolicyEvaluator(
        relationshipResolver = relationshipResolver,
        enforcementModeResolver = EnforcementModeResolver { mode },
        breakGlassAccessor = BreakGlassAccessor { breakGlassReason },
    )

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
        assertEquals("policy-spine-v20", decision.policyVersion)
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

    @Test
    fun `smart v2 scopes authorize by permission direction`() {
        val organizationId = OrganizationId(UUID.randomUUID())
        val clinician = principal(
            organizationId = organizationId,
            roles = listOf(MembershipRole.CLINICIAN),
            scopes = "user/Condition.rs",
        )

        assertTrue(
            evaluator.evaluate(
                clinician,
                PolicyEvaluationRequest(PolicyResourceType.CONDITION, PolicyOperation.READ, organizationId),
            ).allowed,
        )
        val writeDenied = evaluator.evaluate(
            clinician,
            PolicyEvaluationRequest(PolicyResourceType.CONDITION, PolicyOperation.WRITE, organizationId),
        )
        assertFalse(writeDenied.allowed)
        assertEquals(PolicyReasonCode.INSUFFICIENT_SCOPE, writeDenied.reasonCode)

        val writer = principal(
            organizationId = organizationId,
            roles = listOf(MembershipRole.CLINICIAN),
            scopes = "user/Condition.cud",
        )
        assertTrue(
            evaluator.evaluate(
                writer,
                PolicyEvaluationRequest(PolicyResourceType.CONDITION, PolicyOperation.WRITE, organizationId),
            ).allowed,
        )
        assertFalse(
            evaluator.evaluate(
                writer,
                PolicyEvaluationRequest(PolicyResourceType.CONDITION, PolicyOperation.READ, organizationId),
            ).allowed,
        )
    }

    @Test
    fun `patient context scopes never authorize without launch context`() {
        val organizationId = OrganizationId(UUID.randomUUID())
        val clinician = principal(
            organizationId = organizationId,
            roles = listOf(MembershipRole.CLINICIAN),
            scopes = "patient/Patient.read patient/*.cruds",
        )

        val decision = evaluator.evaluate(
            clinician,
            PolicyEvaluationRequest(PolicyResourceType.PATIENT, PolicyOperation.READ, organizationId),
        )
        assertFalse(decision.allowed)
        assertEquals(PolicyReasonCode.INSUFFICIENT_SCOPE, decision.reasonCode)
    }

    @Test
    fun `chart requires a wildcard resource scope`() {
        val organizationId = OrganizationId(UUID.randomUUID())

        val wildcardReader = principal(
            organizationId = organizationId,
            roles = listOf(MembershipRole.CLINICIAN),
            scopes = "user/*.rs",
        )
        assertTrue(
            evaluator.evaluate(
                wildcardReader,
                PolicyEvaluationRequest(PolicyResourceType.CHART, PolicyOperation.READ, organizationId),
            ).allowed,
        )

        val narrowReader = principal(
            organizationId = organizationId,
            roles = listOf(MembershipRole.CLINICIAN),
            scopes = "user/Patient.read user/Observation.read",
        )
        val denied = evaluator.evaluate(
            narrowReader,
            PolicyEvaluationRequest(PolicyResourceType.CHART, PolicyOperation.READ, organizationId),
        )
        assertFalse(denied.allowed)
        assertEquals(PolicyReasonCode.INSUFFICIENT_SCOPE, denied.reasonCode)
    }

    @Test
    fun `clinical record rules shadow resolve the relationship when the patient is known`() {
        val organizationId = OrganizationId(UUID.randomUUID())
        val patientId = UUID.randomUUID()
        val clinician = principal(
            organizationId = organizationId,
            roles = listOf(MembershipRole.CLINICIAN),
            scopes = "user/*.read",
        )
        val resolver = RelationshipResolver { resolvedOrg, resolvedUser, resolvedPatient ->
            assertEquals(organizationId, resolvedOrg)
            assertEquals(clinician.subject.userId, resolvedUser)
            assertEquals(patientId, resolvedPatient)
            RelationshipBasis.CARE_TEAM_MEMBER
        }

        val decision = evaluator(relationshipResolver = resolver).evaluate(
            clinician,
            PolicyEvaluationRequest(
                resourceType = PolicyResourceType.CONDITION,
                operation = PolicyOperation.READ,
                organizationId = organizationId,
                patientId = patientId,
            ),
        )

        assertTrue(decision.allowed)
        assertEquals(RelationshipBasis.CARE_TEAM_MEMBER, decision.relationshipBasis)
    }

    @Test
    fun `an allowed clinical read without a relationship stays allowed with a null basis`() {
        val organizationId = OrganizationId(UUID.randomUUID())
        val clinician = principal(
            organizationId = organizationId,
            roles = listOf(MembershipRole.CLINICIAN),
            scopes = "user/*.read",
        )

        val decision = evaluator().evaluate(
            clinician,
            PolicyEvaluationRequest(
                resourceType = PolicyResourceType.OBSERVATION,
                operation = PolicyOperation.READ,
                organizationId = organizationId,
                patientId = UUID.randomUUID(),
            ),
        )

        assertTrue(decision.allowed)
        assertEquals(null, decision.relationshipBasis)
    }

    @Test
    fun `org wide rules never consult the resolver even with a patient in the request`() {
        val organizationId = OrganizationId(UUID.randomUUID())
        val clinician = principal(
            organizationId = organizationId,
            roles = listOf(MembershipRole.CLINICIAN),
            scopes = "user/*.read user/*.write",
        )

        listOf(PolicyResourceType.PATIENT, PolicyResourceType.ENCOUNTER, PolicyResourceType.CARE_TEAM)
            .forEach { resourceType ->
                val decision = evaluator(relationshipResolver = untouchableResolver).evaluate(
                    clinician,
                    PolicyEvaluationRequest(
                        resourceType = resourceType,
                        operation = PolicyOperation.READ,
                        organizationId = organizationId,
                        patientId = UUID.randomUUID(),
                    ),
                )
                assertTrue(decision.allowed, "expected $resourceType read to be allowed")
                assertEquals(null, decision.relationshipBasis)
            }
    }

    @Test
    fun `the resolver is not consulted without a patient or on denied decisions`() {
        val organizationId = OrganizationId(UUID.randomUUID())

        // No patient in the request.
        val clinician = principal(
            organizationId = organizationId,
            roles = listOf(MembershipRole.CLINICIAN),
            scopes = "user/*.read",
        )
        val noPatient = evaluator(relationshipResolver = untouchableResolver).evaluate(
            clinician,
            PolicyEvaluationRequest(
                resourceType = PolicyResourceType.CONDITION,
                operation = PolicyOperation.READ,
                organizationId = organizationId,
            ),
        )
        assertTrue(noPatient.allowed)
        assertEquals(null, noPatient.relationshipBasis)

        // Role denial precedes relationship interest.
        val staff = principal(
            organizationId = organizationId,
            roles = listOf(MembershipRole.STAFF),
            scopes = "user/*.read",
        )
        val denied = evaluator(relationshipResolver = untouchableResolver).evaluate(
            staff,
            PolicyEvaluationRequest(
                resourceType = PolicyResourceType.CONDITION,
                operation = PolicyOperation.READ,
                organizationId = organizationId,
                patientId = UUID.randomUUID(),
            ),
        )
        assertFalse(denied.allowed)
        assertEquals(null, denied.relationshipBasis)
    }

    @Test
    fun `enforced mode denies clinical access without a treatment relationship`() {
        val organizationId = OrganizationId(UUID.randomUUID())
        val clinician = principal(
            organizationId = organizationId,
            roles = listOf(MembershipRole.CLINICIAN),
            scopes = "user/*.read user/*.write",
        )

        listOf(PolicyOperation.READ, PolicyOperation.WRITE).forEach { operation ->
            val decision = evaluator(mode = CompartmentEnforcementMode.ENFORCED).evaluate(
                clinician,
                PolicyEvaluationRequest(
                    resourceType = PolicyResourceType.CONDITION,
                    operation = operation,
                    organizationId = organizationId,
                    patientId = UUID.randomUUID(),
                ),
            )
            assertFalse(decision.allowed, "expected $operation to be denied without a relationship")
            assertEquals(PolicyReasonCode.NO_TREATMENT_RELATIONSHIP, decision.reasonCode)
            assertEquals(null, decision.relationshipBasis)
        }
    }

    @Test
    fun `enforced mode allows clinical access with a treatment relationship`() {
        val organizationId = OrganizationId(UUID.randomUUID())
        val clinician = principal(
            organizationId = organizationId,
            roles = listOf(MembershipRole.CLINICIAN),
            scopes = "user/*.read",
        )

        val decision = evaluator(
            relationshipResolver = { _, _, _ -> RelationshipBasis.ENCOUNTER_DERIVED },
            mode = CompartmentEnforcementMode.ENFORCED,
        ).evaluate(
            clinician,
            PolicyEvaluationRequest(
                resourceType = PolicyResourceType.OBSERVATION,
                operation = PolicyOperation.READ,
                organizationId = organizationId,
                patientId = UUID.randomUUID(),
            ),
        )

        assertTrue(decision.allowed)
        assertEquals(RelationshipBasis.ENCOUNTER_DERIVED, decision.relationshipBasis)
        assertEquals(null, decision.purposeOfUse)
    }

    @Test
    fun `break-glass rescues enforced reads but never writes`() {
        val organizationId = OrganizationId(UUID.randomUUID())
        val clinician = principal(
            organizationId = organizationId,
            roles = listOf(MembershipRole.CLINICIAN),
            scopes = "user/*.read user/*.write",
        )
        val breakGlassEvaluator = evaluator(
            mode = CompartmentEnforcementMode.ENFORCED,
            breakGlassReason = "unresponsive patient in the ED",
        )

        val read = breakGlassEvaluator.evaluate(
            clinician,
            PolicyEvaluationRequest(
                resourceType = PolicyResourceType.CONDITION,
                operation = PolicyOperation.READ,
                organizationId = organizationId,
                patientId = UUID.randomUUID(),
            ),
        )
        assertTrue(read.allowed)
        assertEquals(RelationshipBasis.BREAK_GLASS, read.relationshipBasis)
        assertEquals("ETREAT", read.purposeOfUse)
        assertEquals("unresponsive patient in the ED", read.breakGlassReason)

        val write = breakGlassEvaluator.evaluate(
            clinician,
            PolicyEvaluationRequest(
                resourceType = PolicyResourceType.CONDITION,
                operation = PolicyOperation.WRITE,
                organizationId = organizationId,
                patientId = UUID.randomUUID(),
            ),
        )
        assertFalse(write.allowed)
        assertEquals(PolicyReasonCode.NO_TREATMENT_RELATIONSHIP, write.reasonCode)
    }

    @Test
    fun `off mode skips relationship resolution entirely`() {
        val organizationId = OrganizationId(UUID.randomUUID())
        val clinician = principal(
            organizationId = organizationId,
            roles = listOf(MembershipRole.CLINICIAN),
            scopes = "user/*.read",
        )

        val decision = evaluator(
            relationshipResolver = untouchableResolver,
            mode = CompartmentEnforcementMode.OFF,
        ).evaluate(
            clinician,
            PolicyEvaluationRequest(
                resourceType = PolicyResourceType.CONDITION,
                operation = PolicyOperation.READ,
                organizationId = organizationId,
                patientId = UUID.randomUUID(),
            ),
        )

        assertTrue(decision.allowed)
        assertEquals(null, decision.relationshipBasis)
    }

    @Test
    fun `principals without a user identity fail closed in enforced mode`() {
        val organizationId = OrganizationId(UUID.randomUUID())
        val systemPrincipal = principal(
            organizationId = organizationId,
            roles = listOf(MembershipRole.CLINICIAN),
            scopes = "user/*.read",
        ).let { it.copy(subject = it.subject.copy(userId = null)) }

        val decision = evaluator(
            relationshipResolver = untouchableResolver,
            mode = CompartmentEnforcementMode.ENFORCED,
        ).evaluate(
            systemPrincipal,
            PolicyEvaluationRequest(
                resourceType = PolicyResourceType.CONDITION,
                operation = PolicyOperation.READ,
                organizationId = organizationId,
                patientId = UUID.randomUUID(),
            ),
        )

        assertFalse(decision.allowed)
        assertEquals(PolicyReasonCode.NO_TREATMENT_RELATIONSHIP, decision.reasonCode)
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
        launchPatientId: UUID? = null,
    ): SecurityPrincipal =
        SecurityPrincipal(
            subject = AuthenticatedSubject(
                externalSubject = "subject-${UUID.randomUUID()}",
                userId = UserId(UUID.randomUUID()),
                clientId = OAuthClientId(UUID.randomUUID()),
                scopes = SecurityScope.parse(scopes),
                launchPatientId = launchPatientId,
            ),
            organization = OrganizationContext(organizationId),
            membership = MembershipContext(
                membershipId = MembershipId(UUID.randomUUID()),
                roles = roles,
            ),
        )

    @Test
    fun `launch context unlocks patient scopes for exactly the launched patient`() {
        val organizationId = OrganizationId(UUID.randomUUID())
        val launchedPatient = UUID.randomUUID()
        val clinician = principal(
            organizationId = organizationId,
            roles = listOf(MembershipRole.CLINICIAN),
            scopes = "patient/*.read",
            launchPatientId = launchedPatient,
        )

        val matching = evaluator(relationshipResolver = { _, _, _ -> RelationshipBasis.ENCOUNTER_DERIVED }).evaluate(
            clinician,
            PolicyEvaluationRequest(
                resourceType = PolicyResourceType.CONDITION,
                operation = PolicyOperation.READ,
                organizationId = organizationId,
                patientId = launchedPatient,
            ),
        )
        assertTrue(matching.allowed)

        val mismatched = evaluator().evaluate(
            clinician,
            PolicyEvaluationRequest(
                resourceType = PolicyResourceType.CONDITION,
                operation = PolicyOperation.READ,
                organizationId = organizationId,
                patientId = UUID.randomUUID(),
            ),
        )
        assertFalse(mismatched.allowed)
        assertEquals(PolicyReasonCode.OUTSIDE_PATIENT_CONTEXT, mismatched.reasonCode)
    }

    @Test
    fun `mixed tokens keep org-wide access through their user scopes`() {
        val organizationId = OrganizationId(UUID.randomUUID())
        val clinician = principal(
            organizationId = organizationId,
            roles = listOf(MembershipRole.CLINICIAN),
            scopes = "patient/*.read user/*.read",
            launchPatientId = UUID.randomUUID(),
        )

        val decision = evaluator().evaluate(
            clinician,
            PolicyEvaluationRequest(
                resourceType = PolicyResourceType.CONDITION,
                operation = PolicyOperation.READ,
                organizationId = organizationId,
                patientId = UUID.randomUUID(),
            ),
        )
        assertTrue(decision.allowed)
    }
}
