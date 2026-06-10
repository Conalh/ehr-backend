package dev.ehr.medication

import dev.ehr.identity.MembershipRepository
import dev.ehr.identity.MembershipRole
import dev.ehr.identity.Organization
import dev.ehr.identity.OrganizationRepository
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
class MedicationStatementApiIntegrationTest : PostgresIntegrationTest() {
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
    lateinit var medicationStatementRepository: MedicationStatementRepository

    @Autowired
    lateinit var codingRepository: CodingRepository

    @Autowired
    lateinit var codeableConceptRepository: CodeableConceptRepository

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    lateinit var jwtEncoder: JwtEncoder

    lateinit var medicationConcept: CodeableConcept

    @BeforeEach
    fun setUpConcept() {
        medicationConcept = TerminologyTestFixtures(codingRepository, codeableConceptRepository)
            .findOrCreateConcept(
                system = CanonicalCodeSystems.RXNORM,
                code = "197361",
                display = "Lisinopril 10 MG Oral Tablet",
            )
    }

    @Test
    fun `medication statement endpoints reject unauthenticated requests without audit`() {
        val correlationId = "med-unauth-${UUID.randomUUID()}"

        mockMvc.get("/api/v1/medication-statements/${UUID.randomUUID()}") {
            header("X-Correlation-Id", correlationId)
        }.andExpect {
            status { isUnauthorized() }
        }

        assertEquals(0, auditCount(correlationId))
    }

    @Test
    fun `clinician can record a medication statement and the create is audited`() {
        val correlationId = "med-record-${UUID.randomUUID()}"
        val member = createMember(MembershipRole.CLINICIAN, "user/MedicationStatement.write user/MedicationStatement.read")
        val patient = createPatient(member.organization)

        mockMvc.post("/api/v1/patients/${patient.id.value}/medication-statements") {
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "medicationConceptId": "${medicationConcept.id.value}",
                  "status": "ACTIVE",
                  "dosageText": "10 mg orally once daily",
                  "effectiveStart": "2026-01-01"
                }
            """.trimIndent()
            header("Authorization", "Bearer ${member.token}")
            header("X-Correlation-Id", correlationId)
        }.andExpect {
            status { isCreated() }
            jsonPath("$.id") { exists() }
            jsonPath("$.patientId") { value(patient.id.value.toString()) }
            jsonPath("$.status") { value("active") }
            jsonPath("$.medicationConceptId") { value(medicationConcept.id.value.toString()) }
            jsonPath("$.dosageText") { value("10 mg orally once daily") }
            jsonPath("$.effectiveStart") { value("2026-01-01") }
            jsonPath("$.version") { value(1) }
        }

        val audit = auditRow(correlationId)
        assertEquals("MEDICATION", audit.resourceType)
        assertEquals("CREATE", audit.operation)
        assertEquals("SUCCESS", audit.outcome)
        assertEquals(patient.id.value.toString(), audit.patientId)
        assertEquals("policy-spine-v13", audit.policyVersion)
    }

    @Test
    fun `staff cannot read medication statements`() {
        val correlationId = "med-staff-${UUID.randomUUID()}"
        val member = createMember(MembershipRole.STAFF, "user/*.read user/*.write")
        val patient = createPatient(member.organization)
        val statement = createStatement(member.organization, patient)

        mockMvc.get("/api/v1/medication-statements/${statement.id.value}") {
            header("Authorization", "Bearer ${member.token}")
            header("X-Correlation-Id", correlationId)
        }.andExpect {
            status { isForbidden() }
        }

        val audit = auditRow(correlationId)
        assertEquals("AUTHORIZATION_DENIED", audit.operation)
        assertEquals("INSUFFICIENT_ROLE", audit.policyReasonCode)
    }

    @Test
    fun `cross organization medication statement read returns 404 and audits a failed read`() {
        val correlationId = "med-cross-${UUID.randomUUID()}"
        val member = createMember(MembershipRole.CLINICIAN, "user/MedicationStatement.read")
        val otherOrganization = createOrganization()
        val otherPatient = createPatient(otherOrganization)
        val otherStatement = createStatement(otherOrganization, otherPatient)

        mockMvc.get("/api/v1/medication-statements/${otherStatement.id.value}") {
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
    fun `recording for cross tenant patient or with unknown concept or inverted period fails`() {
        val member = createMember(MembershipRole.CLINICIAN, "user/MedicationStatement.write")
        val patient = createPatient(member.organization)
        val otherOrganization = createOrganization()
        val otherPatient = createPatient(otherOrganization)

        mockMvc.post("/api/v1/patients/${otherPatient.id.value}/medication-statements") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"medicationConceptId":"${medicationConcept.id.value}"}"""
            header("Authorization", "Bearer ${member.token}")
        }.andExpect {
            status { isNotFound() }
        }

        mockMvc.post("/api/v1/patients/${patient.id.value}/medication-statements") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"medicationConceptId":"${UUID.randomUUID()}"}"""
            header("Authorization", "Bearer ${member.token}")
        }.andExpect {
            status { isBadRequest() }
        }

        mockMvc.post("/api/v1/patients/${patient.id.value}/medication-statements") {
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "medicationConceptId": "${medicationConcept.id.value}",
                  "effectiveStart": "2026-05-01",
                  "effectiveEnd": "2026-01-01"
                }
            """.trimIndent()
            header("Authorization", "Bearer ${member.token}")
        }.andExpect {
            status { isBadRequest() }
        }

        assertEquals(0, statementCount(member.organization))
        assertEquals(0, statementCount(otherOrganization))
    }

    @Test
    fun `medication list is audited as a search and excludes other patients`() {
        val correlationId = "med-list-${UUID.randomUUID()}"
        val member = createMember(MembershipRole.CLINICIAN, "user/MedicationStatement.read")
        val patient = createPatient(member.organization)
        val otherPatient = createPatient(member.organization)
        val statement = createStatement(member.organization, patient)
        createStatement(member.organization, otherPatient)

        mockMvc.get("/api/v1/patients/${patient.id.value}/medication-statements") {
            header("Authorization", "Bearer ${member.token}")
            header("X-Correlation-Id", correlationId)
        }.andExpect {
            status { isOk() }
            jsonPath("$.medicationStatements.length()") { value(1) }
            jsonPath("$.medicationStatements[0].id") { value(statement.id.value.toString()) }
        }

        val audit = auditRow(correlationId)
        assertEquals("SEARCH", audit.operation)
        assertEquals("SUCCESS", audit.outcome)
        assertEquals(patient.id.value.toString(), audit.patientId)
    }

    @Test
    fun `clinician without medication scope is denied`() {
        val correlationId = "med-scope-${UUID.randomUUID()}"
        val member = createMember(MembershipRole.CLINICIAN, "user/Observation.read")
        val patient = createPatient(member.organization)
        val statement = createStatement(member.organization, patient)

        mockMvc.get("/api/v1/medication-statements/${statement.id.value}") {
            header("Authorization", "Bearer ${member.token}")
            header("X-Correlation-Id", correlationId)
        }.andExpect {
            status { isForbidden() }
        }

        val audit = auditRow(correlationId)
        assertEquals("AUTHORIZATION_DENIED", audit.operation)
        assertEquals("INSUFFICIENT_SCOPE", audit.policyReasonCode)
    }

    private fun createOrganization(): Organization {
        val suffix = UUID.randomUUID()
        return organizationRepository.create(
            slug = "med-api-org-$suffix",
            displayName = "Med Api Org $suffix",
        )
    }

    private fun createMember(
        role: MembershipRole,
        scopes: String,
    ): MedicationMemberFixture {
        val suffix = UUID.randomUUID()
        val organization = createOrganization()
        val user = userRepository.create(
            externalSubject = "med-api-user-$suffix",
            email = "med-api-user-$suffix@example.test",
            displayName = "Med Api User $suffix",
        )
        val membership = membershipRepository.create(
            organizationId = organization.id,
            userId = user.id,
        )
        membershipRepository.addRole(membership.id, role)

        return MedicationMemberFixture(
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

    private fun createStatement(
        organization: Organization,
        patient: Patient,
    ): MedicationStatement =
        medicationStatementRepository.create(
            MedicationStatementCreateCommand(
                organizationId = organization.id,
                patientId = patient.id,
                medicationConceptId = medicationConcept.id,
                dosageText = "10 mg orally once daily",
            ),
        )

    private fun statementCount(organization: Organization): Int =
        jdbcTemplate.queryForObject(
            "select count(*) from medication_statements where organization_id = ?",
            Int::class.java,
            organization.id.value,
        )!!

    private fun auditCount(correlationId: String): Int =
        jdbcTemplate.queryForObject(
            "select count(*) from audit_events where correlation_id = ?",
            Int::class.java,
            correlationId,
        )!!

    private fun auditRow(correlationId: String): MedicationAuditRow {
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
                MedicationAuditRow(
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

data class MedicationMemberFixture(
    val organization: Organization,
    val user: User,
    val token: String,
)

data class MedicationAuditRow(
    val patientId: String?,
    val resourceType: String,
    val resourceId: String?,
    val operation: String,
    val outcome: String,
    val policyVersion: String?,
    val policyReasonCode: String?,
)
