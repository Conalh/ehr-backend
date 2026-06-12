package dev.ehr.fhir

import dev.ehr.careteam.CareTeamMembership
import dev.ehr.careteam.CareTeamMembershipOrigin
import dev.ehr.careteam.CareTeamRepository
import dev.ehr.careteam.CareTeamRole
import dev.ehr.identity.MembershipRepository
import dev.ehr.identity.MembershipRole
import dev.ehr.identity.Organization
import dev.ehr.identity.OrganizationRepository
import dev.ehr.identity.TenantScope
import dev.ehr.identity.User
import dev.ehr.identity.UserRepository
import dev.ehr.patient.Patient
import dev.ehr.patient.PatientCreateCommand
import dev.ehr.patient.PatientRepository
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
class CareTeamFhirApiIntegrationTest : PostgresIntegrationTest() {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var organizationRepository: OrganizationRepository

    @Autowired
    lateinit var userRepository: UserRepository

    @Autowired
    lateinit var membershipRepository: MembershipRepository

    @Autowired
    lateinit var patientRepository: PatientRepository

    @Autowired
    lateinit var careTeamRepository: CareTeamRepository

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    lateinit var jwtEncoder: JwtEncoder

    @Test
    fun `fhir care team endpoints reject unauthenticated requests without audit`() {
        val correlationId = "fhir-ct-unauth-${UUID.randomUUID()}"

        mockMvc.get("/fhir/r4/CareTeam/${UUID.randomUUID()}") {
            header("X-Correlation-Id", correlationId)
        }.andExpect {
            status { isUnauthorized() }
        }

        assertEquals(0, auditCount(correlationId))
    }

    @Test
    fun `clinician reads the patient care team with coded roles and member identities`() {
        val correlationId = "fhir-ct-read-${UUID.randomUUID()}"
        val member = createMember(MembershipRole.CLINICIAN, "user/CareTeam.read")
        val patient = createPatient(member.organization)
        val colleague = createColleague(member.organization)
        val membership = addMembership(member.organization, patient, colleague, CareTeamRole.ATTENDING)

        mockMvc.get("/fhir/r4/CareTeam/${patient.id.value}") {
            header("Authorization", "Bearer ${member.token}")
            header("X-Correlation-Id", correlationId)
        }.andExpect {
            status { isOk() }
            content { contentTypeCompatibleWith("application/fhir+json") }
            jsonPath("$.resourceType") { value("CareTeam") }
            jsonPath("$.id") { value(patient.id.value.toString()) }
            jsonPath("$.status") { value("active") }
            jsonPath("$.subject.reference") { value("Patient/${patient.id.value}") }
            jsonPath("$.participant.length()") { value(1) }
            jsonPath("$.participant[0].role[0].coding[0].system") { value("urn:ehr:care-team-role") }
            jsonPath("$.participant[0].role[0].coding[0].code") { value("attending") }
            jsonPath("$.participant[0].member.identifier.system") { value("urn:ehr:user-id") }
            jsonPath("$.participant[0].member.identifier.value") { value(colleague.id.value.toString()) }
            jsonPath("$.participant[0].member.display") { value(colleague.displayName) }
            jsonPath("$.participant[0].period.start") { exists() }
        }

        val audit = auditRow(correlationId)
        assertEquals("CARE_TEAM", audit.resourceType)
        assertEquals("SEARCH", audit.operation)
        assertEquals("SUCCESS", audit.outcome)
        assertEquals(patient.id.value.toString(), audit.patientId)

        // Ended memberships disappear; the team itself remains valid.
        // (HAPI omits the empty participant array from the JSON entirely.)
        careTeamRepository.end(TenantScope(member.organization.id), membership.id)
        mockMvc.get("/fhir/r4/CareTeam/${patient.id.value}") {
            header("Authorization", "Bearer ${member.token}")
        }.andExpect {
            status { isOk() }
            jsonPath("$.id") { value(patient.id.value.toString()) }
            jsonPath("$.participant") { doesNotExist() }
        }
    }

    @Test
    fun `cross organization care team read returns operation outcome not found`() {
        val correlationId = "fhir-ct-cross-${UUID.randomUUID()}"
        val member = createMember(MembershipRole.CLINICIAN, "user/CareTeam.read")
        val otherOrganization = createOrganization()
        val otherPatient = createPatient(otherOrganization)

        mockMvc.get("/fhir/r4/CareTeam/${otherPatient.id.value}") {
            header("Authorization", "Bearer ${member.token}")
            header("X-Correlation-Id", correlationId)
        }.andExpect {
            status { isNotFound() }
            jsonPath("$.resourceType") { value("OperationOutcome") }
            jsonPath("$.issue[0].code") { value("not-found") }
        }

        val audit = auditRow(correlationId)
        assertEquals("SEARCH", audit.operation)
        assertEquals("FAILURE", audit.outcome)
    }

    @Test
    fun `non uuid care team id returns operation outcome not found without audit`() {
        val correlationId = "fhir-ct-badid-${UUID.randomUUID()}"
        val member = createMember(MembershipRole.CLINICIAN, "user/CareTeam.read")

        mockMvc.get("/fhir/r4/CareTeam/not-a-uuid") {
            header("Authorization", "Bearer ${member.token}")
            header("X-Correlation-Id", correlationId)
        }.andExpect {
            status { isNotFound() }
            jsonPath("$.resourceType") { value("OperationOutcome") }
        }

        assertEquals(0, auditCount(correlationId))
    }

    @Test
    fun `staff care team read returns operation outcome forbidden`() {
        val correlationId = "fhir-ct-staff-${UUID.randomUUID()}"
        val member = createMember(MembershipRole.STAFF, "user/*.read")
        val patient = createPatient(member.organization)

        mockMvc.get("/fhir/r4/CareTeam/${patient.id.value}") {
            header("Authorization", "Bearer ${member.token}")
            header("X-Correlation-Id", correlationId)
        }.andExpect {
            status { isForbidden() }
            jsonPath("$.resourceType") { value("OperationOutcome") }
            jsonPath("$.issue[0].code") { value("forbidden") }
        }

        val audit = auditRow(correlationId)
        assertEquals("AUTHORIZATION_DENIED", audit.operation)
        assertEquals("INSUFFICIENT_ROLE", audit.policyReasonCode)
    }

    @Test
    fun `fhir care team search returns a singleton bundle in both patient forms`() {
        val correlationId = "fhir-ct-search-${UUID.randomUUID()}"
        val member = createMember(MembershipRole.CLINICIAN, "user/CareTeam.read")
        val patient = createPatient(member.organization)
        val colleague = createColleague(member.organization)
        addMembership(member.organization, patient, colleague, CareTeamRole.COVERING)

        mockMvc.get("/fhir/r4/CareTeam") {
            param("patient", patient.id.value.toString())
            header("Authorization", "Bearer ${member.token}")
            header("X-Correlation-Id", correlationId)
        }.andExpect {
            status { isOk() }
            jsonPath("$.resourceType") { value("Bundle") }
            jsonPath("$.type") { value("searchset") }
            jsonPath("$.total") { value(1) }
            jsonPath("$.entry[0].resource.resourceType") { value("CareTeam") }
            jsonPath("$.entry[0].resource.id") { value(patient.id.value.toString()) }
            jsonPath("$.entry[0].resource.participant[0].role[0].coding[0].code") { value("covering") }
            jsonPath("$.entry[0].fullUrl") {
                value(org.hamcrest.Matchers.endsWith("/fhir/r4/CareTeam/${patient.id.value}"))
            }
            jsonPath("$.entry[0].search.mode") { value("match") }
        }

        val audit = auditRow(correlationId)
        assertEquals("SEARCH", audit.operation)
        assertEquals("SUCCESS", audit.outcome)

        mockMvc.get("/fhir/r4/CareTeam") {
            param("patient", "Patient/${patient.id.value}")
            header("Authorization", "Bearer ${member.token}")
        }.andExpect {
            status { isOk() }
            jsonPath("$.total") { value(1) }
        }
    }

    @Test
    fun `fhir care team search filters by status`() {
        val member = createMember(MembershipRole.CLINICIAN, "user/CareTeam.read")
        val patient = createPatient(member.organization)

        // The served team is always active: matching status returns it,
        // any other an honest empty bundle.
        mockMvc.get("/fhir/r4/CareTeam") {
            param("patient", patient.id.value.toString())
            param("status", "active")
            header("Authorization", "Bearer ${member.token}")
        }.andExpect {
            status { isOk() }
            jsonPath("$.total") { value(1) }
        }
        mockMvc.get("/fhir/r4/CareTeam") {
            param("patient", patient.id.value.toString())
            param("status", "proposed")
            header("Authorization", "Bearer ${member.token}")
        }.andExpect {
            status { isOk() }
            jsonPath("$.total") { value(0) }
        }
    }

    @Test
    fun `fhir care team search without usable patient parameter returns operation outcome invalid`() {
        val member = createMember(MembershipRole.CLINICIAN, "user/CareTeam.read")

        listOf(null, "not-a-uuid").forEach { patientParam ->
            mockMvc.get("/fhir/r4/CareTeam") {
                if (patientParam != null) {
                    param("patient", patientParam)
                }
                header("Authorization", "Bearer ${member.token}")
            }.andExpect {
                status { isBadRequest() }
                jsonPath("$.resourceType") { value("OperationOutcome") }
                jsonPath("$.issue[0].code") { value("invalid") }
            }
        }
    }

    private fun addMembership(
        organization: Organization,
        patient: Patient,
        user: User,
        role: CareTeamRole,
    ): CareTeamMembership =
        careTeamRepository.addMember(
            organizationId = organization.id,
            patientId = patient.id,
            userId = user.id,
            role = role,
            origin = CareTeamMembershipOrigin.EXPLICIT,
            createdBy = user.id,
        )

    private fun createColleague(organization: Organization): User {
        val suffix = UUID.randomUUID()
        val user = userRepository.create(
            externalSubject = "fhir-ct-colleague-$suffix",
            email = "fhir-ct-colleague-$suffix@example.test",
            displayName = "Fhir Ct Colleague $suffix",
        )
        val membership = membershipRepository.create(
            organizationId = organization.id,
            userId = user.id,
        )
        membershipRepository.addRole(membership.id, MembershipRole.CLINICIAN)
        return user
    }

    private fun createOrganization(): Organization {
        val suffix = UUID.randomUUID()
        return organizationRepository.create(
            slug = "fhir-ct-org-$suffix",
            displayName = "Fhir Ct Org $suffix",
        )
    }

    private fun createMember(
        role: MembershipRole,
        scopes: String,
    ): CareTeamFhirMemberFixture {
        val suffix = UUID.randomUUID()
        val organization = createOrganization()
        val user = userRepository.create(
            externalSubject = "fhir-ct-user-$suffix",
            email = "fhir-ct-user-$suffix@example.test",
            displayName = "Fhir Ct User $suffix",
        )
        val membership = membershipRepository.create(
            organizationId = organization.id,
            userId = user.id,
        )
        membershipRepository.addRole(membership.id, role)

        return CareTeamFhirMemberFixture(
            organization = organization,
            user = user,
            token = DevJwtFactory(jwtEncoder).tokenFor(
                user = user,
                organization = organization,
                scopes = scopes,
            ),
        )
    }

    private fun createPatient(organization: Organization): Patient =
        patientRepository.create(
            PatientCreateCommand(
                organizationId = organization.id,
                givenName = "Synthetic",
                familyName = "Patient",
            ),
        )

    private fun auditCount(correlationId: String): Int =
        jdbcTemplate.queryForObject(
            "select count(*) from audit_events where correlation_id = ?",
            Int::class.java,
            correlationId,
        )!!

    private fun auditRow(correlationId: String): CareTeamFhirAuditRow {
        assertEquals(1, auditCount(correlationId), "expected exactly one audit row for $correlationId")
        return jdbcTemplate.queryForObject(
            """
            select patient_id::text, resource_type, operation, outcome, policy_reason_code
            from audit_events
            where correlation_id = ?
            """.trimIndent(),
            { rs, _ ->
                CareTeamFhirAuditRow(
                    patientId = rs.getString("patient_id"),
                    resourceType = rs.getString("resource_type"),
                    operation = rs.getString("operation"),
                    outcome = rs.getString("outcome"),
                    policyReasonCode = rs.getString("policy_reason_code"),
                )
            },
            correlationId,
        )!!
    }
}

data class CareTeamFhirMemberFixture(
    val organization: Organization,
    val user: User,
    val token: String,
)

data class CareTeamFhirAuditRow(
    val patientId: String?,
    val resourceType: String,
    val operation: String,
    val outcome: String,
    val policyReasonCode: String?,
)
