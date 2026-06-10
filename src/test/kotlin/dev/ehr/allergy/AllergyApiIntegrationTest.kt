package dev.ehr.allergy

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
import dev.ehr.terminology.CanonicalCodeSystems
import dev.ehr.terminology.CodeableConcept
import dev.ehr.terminology.CodeableConceptRepository
import dev.ehr.terminology.CodingRepository
import dev.ehr.testsupport.DevJwtFactory
import dev.ehr.testsupport.DevJwtTestConfiguration
import dev.ehr.testsupport.PostgresIntegrationTest
import dev.ehr.testsupport.TerminologyTestFixtures
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.util.UUID

@AutoConfigureMockMvc
@Import(DevJwtTestConfiguration::class)
class AllergyApiIntegrationTest : PostgresIntegrationTest() {
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
    lateinit var allergyRepository: AllergyRepository

    @Autowired
    lateinit var codingRepository: CodingRepository

    @Autowired
    lateinit var codeableConceptRepository: CodeableConceptRepository

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    lateinit var jwtEncoder: JwtEncoder

    lateinit var codeConcept: CodeableConcept

    @BeforeEach
    fun setUpConcept() {
        codeConcept = TerminologyTestFixtures(codingRepository, codeableConceptRepository)
            .findOrCreateConcept(
                system = CanonicalCodeSystems.SNOMED_CT,
                code = "91935009",
                display = "Allergy to peanut",
            )
    }

    @Test
    fun `allergy endpoints reject unauthenticated requests without audit`() {
        val correlationId = "allergy-unauth-${UUID.randomUUID()}"

        mockMvc.get("/api/v1/allergies/${UUID.randomUUID()}") {
            header("X-Correlation-Id", correlationId)
        }.andExpect {
            status { isUnauthorized() }
        }

        assertEquals(0, auditCount(correlationId))
    }

    @Test
    fun `clinician can record an allergy and the create is audited`() {
        val correlationId = "allergy-record-${UUID.randomUUID()}"
        val member = createMember(MembershipRole.CLINICIAN, "user/AllergyIntolerance.write user/AllergyIntolerance.read")
        val patient = createPatient(member.organization)

        mockMvc.post("/api/v1/patients/${patient.id.value}/allergies") {
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "codeConceptId": "${codeConcept.id.value}",
                  "clinicalStatus": "ACTIVE",
                  "verificationStatus": "CONFIRMED",
                  "category": "FOOD",
                  "criticality": "HIGH",
                  "onsetDate": "2020-07-04"
                }
            """.trimIndent()
            header("Authorization", "Bearer ${member.token}")
            header("X-Correlation-Id", correlationId)
        }.andExpect {
            status { isCreated() }
            jsonPath("$.id") { exists() }
            jsonPath("$.patientId") { value(patient.id.value.toString()) }
            jsonPath("$.clinicalStatus") { value("active") }
            jsonPath("$.verificationStatus") { value("confirmed") }
            jsonPath("$.category") { value("food") }
            jsonPath("$.criticality") { value("high") }
            jsonPath("$.onsetDate") { value("2020-07-04") }
            jsonPath("$.version") { value(1) }
        }

        val audit = auditRow(correlationId)
        assertEquals("ALLERGY", audit.resourceType)
        assertEquals("CREATE", audit.operation)
        assertEquals("SUCCESS", audit.outcome)
        assertEquals(patient.id.value.toString(), audit.patientId)
        assertEquals("policy-spine-v14", audit.policyVersion)
    }

    @Test
    fun `staff cannot read or record allergies`() {
        val readCorrelationId = "allergy-staff-read-${UUID.randomUUID()}"
        val member = createMember(MembershipRole.STAFF, "user/*.read user/*.write")
        val patient = createPatient(member.organization)
        val allergy = createAllergy(member.organization, patient)

        mockMvc.get("/api/v1/allergies/${allergy.id.value}") {
            header("Authorization", "Bearer ${member.token}")
            header("X-Correlation-Id", readCorrelationId)
        }.andExpect {
            status { isForbidden() }
        }

        val audit = auditRow(readCorrelationId)
        assertEquals("AUTHORIZATION_DENIED", audit.operation)
        assertEquals("INSUFFICIENT_ROLE", audit.policyReasonCode)
    }

    @Test
    fun `cross organization allergy read returns 404 and audits a failed read`() {
        val correlationId = "allergy-read-cross-${UUID.randomUUID()}"
        val member = createMember(MembershipRole.CLINICIAN, "user/AllergyIntolerance.read")
        val otherOrganization = createOrganization()
        val otherPatient = createPatient(otherOrganization)
        val otherAllergy = createAllergy(otherOrganization, otherPatient)

        mockMvc.get("/api/v1/allergies/${otherAllergy.id.value}") {
            header("Authorization", "Bearer ${member.token}")
            header("X-Correlation-Id", correlationId)
        }.andExpect {
            status { isNotFound() }
        }

        val audit = auditRow(correlationId)
        assertEquals("READ", audit.operation)
        assertEquals("FAILURE", audit.outcome)
        assertNull(audit.patientId)
    }

    @Test
    fun `recording an allergy for another organizations patient returns 404`() {
        val member = createMember(MembershipRole.CLINICIAN, "user/AllergyIntolerance.write")
        val otherOrganization = createOrganization()
        val otherPatient = createPatient(otherOrganization)

        mockMvc.post("/api/v1/patients/${otherPatient.id.value}/allergies") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"codeConceptId":"${codeConcept.id.value}"}"""
            header("Authorization", "Bearer ${member.token}")
        }.andExpect {
            status { isNotFound() }
        }

        assertEquals(0, allergyCount(otherOrganization))
    }

    @Test
    fun `recording an allergy with unknown code concept returns 400`() {
        val member = createMember(MembershipRole.CLINICIAN, "user/AllergyIntolerance.write")
        val patient = createPatient(member.organization)

        mockMvc.post("/api/v1/patients/${patient.id.value}/allergies") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"codeConceptId":"${UUID.randomUUID()}"}"""
            header("Authorization", "Bearer ${member.token}")
        }.andExpect {
            status { isBadRequest() }
        }

        assertEquals(0, allergyCount(member.organization))
    }

    @Test
    fun `allergy list is audited as a search and excludes other patients`() {
        val correlationId = "allergy-list-${UUID.randomUUID()}"
        val member = createMember(MembershipRole.CLINICIAN, "user/AllergyIntolerance.read")
        val patient = createPatient(member.organization)
        val otherPatient = createPatient(member.organization)
        val allergy = createAllergy(member.organization, patient)
        createAllergy(member.organization, otherPatient)

        mockMvc.get("/api/v1/patients/${patient.id.value}/allergies") {
            header("Authorization", "Bearer ${member.token}")
            header("X-Correlation-Id", correlationId)
        }.andExpect {
            status { isOk() }
            jsonPath("$.allergies.length()") { value(1) }
            jsonPath("$.allergies[0].id") { value(allergy.id.value.toString()) }
        }

        val audit = auditRow(correlationId)
        assertEquals("SEARCH", audit.operation)
        assertEquals("SUCCESS", audit.outcome)
        assertEquals(patient.id.value.toString(), audit.patientId)
    }

    @Test
    fun `clinician without allergy scope is denied`() {
        val correlationId = "allergy-scope-${UUID.randomUUID()}"
        val member = createMember(MembershipRole.CLINICIAN, "user/Condition.read")
        val patient = createPatient(member.organization)
        val allergy = createAllergy(member.organization, patient)

        mockMvc.get("/api/v1/allergies/${allergy.id.value}") {
            header("Authorization", "Bearer ${member.token}")
            header("X-Correlation-Id", correlationId)
        }.andExpect {
            status { isForbidden() }
        }

        val audit = auditRow(correlationId)
        assertEquals("AUTHORIZATION_DENIED", audit.operation)
        assertEquals("INSUFFICIENT_SCOPE", audit.policyReasonCode)
    }

    @Test
    fun `wrong tenant allergy list returns 404`() {
        val member = createMember(MembershipRole.CLINICIAN, "user/AllergyIntolerance.read")
        val otherOrganization = createOrganization()
        val otherPatient = createPatient(otherOrganization)
        createAllergy(otherOrganization, otherPatient)

        mockMvc.get("/api/v1/patients/${otherPatient.id.value}/allergies") {
            header("Authorization", "Bearer ${member.token}")
        }.andExpect {
            status { isNotFound() }
        }
    }

    private fun createOrganization(): Organization {
        val suffix = UUID.randomUUID()
        return organizationRepository.create(
            slug = "allergy-api-org-$suffix",
            displayName = "Allergy Api Org $suffix",
        )
    }

    private fun createMember(
        role: MembershipRole,
        scopes: String,
    ): AllergyMemberFixture {
        val suffix = UUID.randomUUID()
        val organization = createOrganization()
        val user = userRepository.create(
            externalSubject = "allergy-api-user-$suffix",
            email = "allergy-api-user-$suffix@example.test",
            displayName = "Allergy Api User $suffix",
        )
        val membership = membershipRepository.create(
            organizationId = organization.id,
            userId = user.id,
        )
        membershipRepository.addRole(membership.id, role)

        return AllergyMemberFixture(
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

    private fun createAllergy(
        organization: Organization,
        patient: Patient,
    ): Allergy =
        allergyRepository.create(
            AllergyCreateCommand(
                organizationId = organization.id,
                patientId = patient.id,
                codeConceptId = codeConcept.id,
            ),
        )

    private fun allergyCount(organization: Organization): Int =
        jdbcTemplate.queryForObject(
            "select count(*) from allergies where organization_id = ?",
            Int::class.java,
            organization.id.value,
        )!!

    private fun auditCount(correlationId: String): Int =
        jdbcTemplate.queryForObject(
            "select count(*) from audit_events where correlation_id = ?",
            Int::class.java,
            correlationId,
        )!!

    private fun auditRow(correlationId: String): AllergyAuditRow {
        assertEquals(1, auditCount(correlationId), "expected exactly one audit row for $correlationId")
        return jdbcTemplate.queryForObject(
            """
            select
              patient_id::text,
              resource_type,
              resource_id::text,
              operation,
              outcome,
              policy_version,
              policy_reason_code
            from audit_events
            where correlation_id = ?
            """.trimIndent(),
            { rs, _ ->
                AllergyAuditRow(
                    patientId = rs.getString("patient_id"),
                    resourceType = rs.getString("resource_type"),
                    resourceId = rs.getString("resource_id"),
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

data class AllergyMemberFixture(
    val organization: Organization,
    val user: User,
    val token: String,
)

data class AllergyAuditRow(
    val patientId: String?,
    val resourceType: String,
    val resourceId: String?,
    val operation: String,
    val outcome: String,
    val policyVersion: String?,
    val policyReasonCode: String?,
)
