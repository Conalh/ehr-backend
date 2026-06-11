package dev.ehr.fhir

import dev.ehr.identity.MembershipRepository
import dev.ehr.identity.MembershipRole
import dev.ehr.identity.Organization
import dev.ehr.identity.OrganizationRepository
import dev.ehr.identity.Practitioner
import dev.ehr.identity.PractitionerRepository
import dev.ehr.identity.User
import dev.ehr.identity.UserRepository
import dev.ehr.testsupport.DevJwtFactory
import dev.ehr.testsupport.DevJwtTestConfiguration
import dev.ehr.testsupport.PostgresIntegrationTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import java.util.UUID

@AutoConfigureMockMvc
@Import(DevJwtTestConfiguration::class)
class PractitionerFhirApiIntegrationTest : PostgresIntegrationTest() {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var organizationRepository: OrganizationRepository

    @Autowired
    lateinit var userRepository: UserRepository

    @Autowired
    lateinit var membershipRepository: MembershipRepository

    @Autowired
    lateinit var practitionerRepository: PractitionerRepository

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    lateinit var jwtEncoder: JwtEncoder

    @Test
    fun `fhir practitioner endpoints reject unauthenticated requests without audit`() {
        val correlationId = "fhir-pract-unauth-${UUID.randomUUID()}"

        mockMvc.get("/fhir/r4/Practitioner/${UUID.randomUUID()}") {
            header("X-Correlation-Id", correlationId)
        }.andExpect {
            status { isUnauthorized() }
        }

        assertEquals(0, auditCount(correlationId))
    }

    @Test
    fun `clinicians and staff read same organization practitioners with audit`() {
        val correlationId = "fhir-pract-read-${UUID.randomUUID()}"
        val member = createMember(MembershipRole.CLINICIAN, "user/Practitioner.read")
        val practitioner = createPractitioner(member.organization)

        mockMvc.get("/fhir/r4/Practitioner/${practitioner.id.value}") {
            header("Authorization", "Bearer ${member.token}")
            header("X-Correlation-Id", correlationId)
        }.andExpect {
            status { isOk() }
            content { contentTypeCompatibleWith("application/fhir+json") }
            jsonPath("$.resourceType") { value("Practitioner") }
            jsonPath("$.id") { value(practitioner.id.value.toString()) }
            jsonPath("$.active") { value(true) }
            jsonPath("$.name[0].text") { value(practitioner.displayName) }
            jsonPath("$.identifier[0].system") { value("http://hl7.org/fhir/sid/us-npi") }
            jsonPath("$.identifier[0].value") { value(practitioner.npi) }
        }

        val audit = auditRow(correlationId)
        assertEquals("PRACTITIONER", audit.resourceType)
        assertEquals("READ", audit.operation)
        assertEquals("SUCCESS", audit.outcome)
        assertEquals("policy-spine-v20", audit.policyVersion)

        // Staff retain read access (directory data).
        val staff = createMemberInOrganization(member.organization, MembershipRole.STAFF, "user/*.read")
        mockMvc.get("/fhir/r4/Practitioner/${practitioner.id.value}") {
            header("Authorization", "Bearer ${staff.token}")
        }.andExpect {
            status { isOk() }
        }
    }

    @Test
    fun `cross organization practitioner reads fail closed`() {
        val correlationId = "fhir-pract-cross-${UUID.randomUUID()}"
        val member = createMember(MembershipRole.CLINICIAN, "user/Practitioner.read")
        val otherOrganization = createOrganization()
        val foreignPractitioner = createPractitioner(otherOrganization)

        mockMvc.get("/fhir/r4/Practitioner/${foreignPractitioner.id.value}") {
            header("Authorization", "Bearer ${member.token}")
            header("X-Correlation-Id", correlationId)
        }.andExpect {
            status { isNotFound() }
            jsonPath("$.resourceType") { value("OperationOutcome") }
        }

        val audit = auditRow(correlationId)
        assertEquals("READ", audit.operation)
        assertEquals("FAILURE", audit.outcome)
    }

    @Test
    fun `org admins are not in the practitioner read rule`() {
        val correlationId = "fhir-pract-admin-${UUID.randomUUID()}"
        val admin = createMember(MembershipRole.ORG_ADMIN, "user/*.read")
        val practitioner = createPractitioner(admin.organization)

        mockMvc.get("/fhir/r4/Practitioner/${practitioner.id.value}") {
            header("Authorization", "Bearer ${admin.token}")
            header("X-Correlation-Id", correlationId)
        }.andExpect {
            status { isForbidden() }
        }
        assertEquals("INSUFFICIENT_ROLE", auditRow(correlationId).policyReasonCode)
    }

    private fun createPractitioner(organization: Organization): Practitioner {
        val colleague = createUser(organization, MembershipRole.CLINICIAN)
        // NPIs are globally unique.
        return practitionerRepository.create(
            userId = colleague.id,
            displayName = colleague.displayName,
            npi = (1_000_000_000L..9_999_999_999L).random().toString(),
        )
    }

    private fun createUser(
        organization: Organization,
        role: MembershipRole,
    ): User {
        val suffix = UUID.randomUUID()
        val user = userRepository.create(
            externalSubject = "fhir-pract-user-$suffix",
            email = "fhir-pract-user-$suffix@example.test",
            displayName = "Fhir Pract User $suffix",
        )
        val membership = membershipRepository.create(
            organizationId = organization.id,
            userId = user.id,
        )
        membershipRepository.addRole(membership.id, role)
        return user
    }

    private fun createOrganization(): Organization {
        val suffix = UUID.randomUUID()
        return organizationRepository.create(
            slug = "fhir-pract-org-$suffix",
            displayName = "Fhir Pract Org $suffix",
        )
    }

    private fun createMember(
        role: MembershipRole,
        scopes: String,
    ): PractitionerMemberFixture = createMemberInOrganization(createOrganization(), role, scopes)

    private fun createMemberInOrganization(
        organization: Organization,
        role: MembershipRole,
        scopes: String,
    ): PractitionerMemberFixture {
        val user = createUser(organization, role)
        return PractitionerMemberFixture(
            organization = organization,
            token = DevJwtFactory(jwtEncoder).tokenFor(
                user = user,
                organization = organization,
                scopes = scopes,
            ),
        )
    }

    private fun auditCount(correlationId: String): Int =
        jdbcTemplate.queryForObject(
            "select count(*) from audit_events where correlation_id = ?",
            Int::class.java,
            correlationId,
        )!!

    private fun auditRow(correlationId: String): PractitionerAuditRow {
        assertEquals(1, auditCount(correlationId), "expected exactly one audit row for $correlationId")
        return jdbcTemplate.queryForObject(
            """
            select resource_type, operation, outcome, policy_version, policy_reason_code
            from audit_events
            where correlation_id = ?
            """.trimIndent(),
            { rs, _ ->
                PractitionerAuditRow(
                    resourceType = rs.getString("resource_type"),
                    operation = rs.getString("operation"),
                    outcome = rs.getString("outcome"),
                    policyVersion = rs.getString("policy_version"),
                    policyReasonCode = rs.getString("policy_reason_code"),
                )
            },
            correlationId,
        )!!
    }
}

data class PractitionerMemberFixture(
    val organization: Organization,
    val token: String,
)

data class PractitionerAuditRow(
    val resourceType: String,
    val operation: String,
    val outcome: String,
    val policyVersion: String?,
    val policyReasonCode: String?,
)
