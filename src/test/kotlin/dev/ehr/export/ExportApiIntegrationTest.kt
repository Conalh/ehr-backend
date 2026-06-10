package dev.ehr.export

import ca.uhn.fhir.context.FhirContext
import dev.ehr.condition.ConditionCreateCommand
import dev.ehr.condition.ConditionRepository
import dev.ehr.identity.MembershipRepository
import dev.ehr.identity.MembershipRole
import dev.ehr.identity.Organization
import dev.ehr.identity.OrganizationRepository
import dev.ehr.identity.TenantScope
import dev.ehr.identity.User
import dev.ehr.identity.UserRepository
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
import org.hl7.fhir.r4.model.Condition
import org.hl7.fhir.r4.model.Patient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.util.UUID

@AutoConfigureMockMvc
@Import(DevJwtTestConfiguration::class)
class ExportApiIntegrationTest : PostgresIntegrationTest() {
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
    lateinit var conditionRepository: ConditionRepository

    @Autowired
    lateinit var exportJobRepository: ExportJobRepository

    @Autowired
    lateinit var codingRepository: CodingRepository

    @Autowired
    lateinit var codeableConceptRepository: CodeableConceptRepository

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    lateinit var jwtEncoder: JwtEncoder

    lateinit var snomedConcept: CodeableConcept

    private val fhirContext: FhirContext = FhirContext.forR4()

    @BeforeEach
    fun setUpConcept() {
        snomedConcept = TerminologyTestFixtures(codingRepository, codeableConceptRepository)
            .findOrCreateConcept(
                system = CanonicalCodeSystems.SNOMED_CT,
                code = "38341003",
                display = "Hypertensive disorder",
            )
    }

    @Test
    fun `export request produces valid tenant scoped ndjson files with audit trail`() {
        val requestCorrelationId = "export-request-${UUID.randomUUID()}"
        val downloadCorrelationId = "export-download-${UUID.randomUUID()}"
        val member = createMember(MembershipRole.CLINICIAN, "user/*.read user/*.write")
        val otherOrganization = createOrganizationWithData()
        val patient = patientRepository.create(
            PatientCreateCommand(
                organizationId = member.organization.id,
                givenName = "Synthetic",
                familyName = "Patient",
            ),
        )
        conditionRepository.create(
            ConditionCreateCommand(
                organizationId = member.organization.id,
                patientId = patient.id,
                codeConceptId = snomedConcept.id,
            ),
        )

        // request
        val response = mockMvc.post("/api/v1/export-jobs") {
            header("Authorization", "Bearer ${member.token}")
            header("X-Correlation-Id", requestCorrelationId)
        }.andExpect {
            status { isAccepted() }
            jsonPath("$.status") { value("pending") }
        }.andReturn().response.contentAsString
        val jobId = UUID.fromString(Regex("\"id\":\"([0-9a-f-]+)\"").find(response)!!.groupValues[1])

        val requestAudit = auditRow(requestCorrelationId)
        assertEquals("EXPORT", requestAudit.resourceType)
        assertEquals("EXPORT", requestAudit.operation)
        assertEquals("SUCCESS", requestAudit.outcome)

        // poll until the async processor completes
        val scope = TenantScope(member.organization.id)
        val deadline = System.currentTimeMillis() + 30_000
        var status: ExportJobStatus
        do {
            Thread.sleep(250)
            status = exportJobRepository.findById(scope, jobId)!!.status
        } while (status != ExportJobStatus.COMPLETED && status != ExportJobStatus.FAILED &&
            System.currentTimeMillis() < deadline
        )
        assertEquals(ExportJobStatus.COMPLETED, status)

        // status endpoint lists one file per served type with download urls
        mockMvc.get("/api/v1/export-jobs/$jobId") {
            header("Authorization", "Bearer ${member.token}")
        }.andExpect {
            status { isOk() }
            jsonPath("$.status") { value("completed") }
            jsonPath("$.files.length()") { value(9) }
            jsonPath("$.files[?(@.resourceType=='Patient')].resourceCount") { value(1) }
            jsonPath("$.files[?(@.resourceType=='Condition')].resourceCount") { value(1) }
        }

        // file-creation audits from the async worker carry the requester
        val fileAuditCount = jdbcTemplate.queryForObject(
            """
            select count(*) from audit_events
            where resource_type = 'EXPORT_FILE' and operation = 'SYSTEM'
              and organization_id = ? and subject_user_id = ?
            """.trimIndent(),
            Int::class.java,
            member.organization.id.value,
            member.user.id.value,
        )
        assertEquals(9, fileAuditCount)

        // download Patient ndjson: every line parses as Patient, only this org's data
        val patientNdjson = mockMvc.get("/api/v1/export-jobs/$jobId/files/Patient") {
            header("Authorization", "Bearer ${member.token}")
            header("X-Correlation-Id", downloadCorrelationId)
        }.andExpect {
            status { isOk() }
            content { contentTypeCompatibleWith("application/fhir+ndjson") }
        }.andReturn().response.contentAsString
        val patientLines = patientNdjson.trim().lines().filter { it.isNotBlank() }
        assertEquals(1, patientLines.size)
        val parsedPatient = fhirContext.newJsonParser().parseResource(Patient::class.java, patientLines[0])
        assertEquals(patient.id.value.toString(), parsedPatient.idElement.idPart)

        val conditionNdjson = mockMvc.get("/api/v1/export-jobs/$jobId/files/Condition") {
            header("Authorization", "Bearer ${member.token}")
        }.andReturn().response.contentAsString
        val conditionLines = conditionNdjson.trim().lines().filter { it.isNotBlank() }
        assertEquals(1, conditionLines.size)
        fhirContext.newJsonParser().parseResource(Condition::class.java, conditionLines[0])

        val downloadAudit = auditRow(downloadCorrelationId)
        assertEquals("EXPORT", downloadAudit.operation)
        assertEquals("SUCCESS", downloadAudit.outcome)

        // the other organization's data never leaked into this export
        assertTrue(otherOrganization.id != member.organization.id)
        assertTrue(!patientNdjson.contains(otherOrganization.id.value.toString()))
    }

    @Test
    fun `export jobs are tenant isolated and role gated`() {
        val deniedCorrelationId = "export-denied-${UUID.randomUUID()}"
        val member = createMember(MembershipRole.CLINICIAN, "user/*.read user/*.write")
        val staff = createMember(MembershipRole.STAFF, "user/*.read user/*.write")
        val outsider = createMember(MembershipRole.CLINICIAN, "user/*.read user/*.write")

        // staff denied
        mockMvc.post("/api/v1/export-jobs") {
            header("Authorization", "Bearer ${staff.token}")
            header("X-Correlation-Id", deniedCorrelationId)
        }.andExpect {
            status { isForbidden() }
        }
        assertEquals("INSUFFICIENT_ROLE", auditRow(deniedCorrelationId).policyReasonCode)

        // narrow scopes denied
        val narrow = createMember(MembershipRole.CLINICIAN, "user/Patient.read user/Patient.write")
        mockMvc.post("/api/v1/export-jobs") {
            header("Authorization", "Bearer ${narrow.token}")
        }.andExpect {
            status { isForbidden() }
        }

        // cross-tenant status/download invisible
        val response = mockMvc.post("/api/v1/export-jobs") {
            header("Authorization", "Bearer ${member.token}")
        }.andExpect {
            status { isAccepted() }
        }.andReturn().response.contentAsString
        val jobId = UUID.fromString(Regex("\"id\":\"([0-9a-f-]+)\"").find(response)!!.groupValues[1])

        mockMvc.get("/api/v1/export-jobs/$jobId") {
            header("Authorization", "Bearer ${outsider.token}")
        }.andExpect {
            status { isNotFound() }
        }
        mockMvc.get("/api/v1/export-jobs/$jobId/files/Patient") {
            header("Authorization", "Bearer ${outsider.token}")
        }.andExpect {
            status { isNotFound() }
        }

        // unauthenticated
        mockMvc.post("/api/v1/export-jobs")
            .andExpect {
                status { isUnauthorized() }
            }
    }

    private fun createOrganizationWithData(): Organization {
        val suffix = UUID.randomUUID()
        val organization = organizationRepository.create(
            slug = "export-other-org-$suffix",
            displayName = "Export Other Org $suffix",
        )
        patientRepository.create(
            PatientCreateCommand(
                organizationId = organization.id,
                givenName = "Other",
                familyName = "Patient",
            ),
        )
        return organization
    }

    private fun createMember(
        role: MembershipRole,
        scopes: String,
    ): ExportMemberFixture {
        val suffix = UUID.randomUUID()
        val organization = organizationRepository.create(
            slug = "export-org-$suffix",
            displayName = "Export Org $suffix",
        )
        val user = userRepository.create(
            externalSubject = "export-user-$suffix",
            email = "export-user-$suffix@example.test",
            displayName = "Export User $suffix",
        )
        val membership = membershipRepository.create(
            organizationId = organization.id,
            userId = user.id,
        )
        membershipRepository.addRole(membership.id, role)

        return ExportMemberFixture(
            organization = organization,
            user = user,
            token = DevJwtFactory(jwtEncoder).tokenFor(
                user = user,
                organization = organization,
                scopes = scopes,
            ),
        )
    }

    private fun auditRow(correlationId: String): ExportAuditRow {
        val count = jdbcTemplate.queryForObject(
            "select count(*) from audit_events where correlation_id = ?",
            Int::class.java,
            correlationId,
        )!!
        assertEquals(1, count, "expected exactly one audit row for $correlationId")
        return jdbcTemplate.queryForObject(
            """
            select resource_type, operation, outcome, policy_reason_code
            from audit_events
            where correlation_id = ?
            """.trimIndent(),
            { rs, _ ->
                ExportAuditRow(
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

data class ExportMemberFixture(
    val organization: Organization,
    val user: User,
    val token: String,
)

data class ExportAuditRow(
    val resourceType: String,
    val operation: String,
    val outcome: String,
    val policyReasonCode: String?,
)
