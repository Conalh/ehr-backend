package dev.ehr.diagnostics

import dev.ehr.identity.MembershipRepository
import dev.ehr.identity.MembershipRole
import dev.ehr.identity.Organization
import dev.ehr.identity.OrganizationRepository
import dev.ehr.identity.TenantScope
import dev.ehr.identity.User
import dev.ehr.identity.UserRepository
import dev.ehr.observation.Observation
import dev.ehr.observation.ObservationCategory
import dev.ehr.observation.ObservationCreateCommand
import dev.ehr.observation.ObservationRepository
import dev.ehr.observation.ObservationValue
import dev.ehr.order.Order
import dev.ehr.order.OrderCreateCommand
import dev.ehr.order.OrderRepository
import dev.ehr.order.OrderStatus
import dev.ehr.patient.Patient
import dev.ehr.patient.PatientCreateCommand
import dev.ehr.patient.PatientRepository
import dev.ehr.provenance.ProvenanceActivity
import dev.ehr.provenance.ProvenanceRepository
import dev.ehr.terminology.CanonicalCodeSystems
import dev.ehr.terminology.CodeableConcept
import dev.ehr.terminology.CodeableConceptRepository
import dev.ehr.terminology.CodingRepository
import dev.ehr.testsupport.DevJwtFactory
import dev.ehr.testsupport.DevJwtTestConfiguration
import dev.ehr.testsupport.PostgresIntegrationTest
import dev.ehr.testsupport.TerminologyTestFixtures
import org.junit.jupiter.api.Assertions.assertEquals
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
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

@AutoConfigureMockMvc
@Import(DevJwtTestConfiguration::class)
class DiagnosticReportApiIntegrationTest : PostgresIntegrationTest() {
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
    lateinit var orderRepository: OrderRepository

    @Autowired
    lateinit var observationRepository: ObservationRepository

    @Autowired
    lateinit var diagnosticReportRepository: DiagnosticReportRepository

    @Autowired
    lateinit var provenanceRepository: ProvenanceRepository

    @Autowired
    lateinit var codingRepository: CodingRepository

    @Autowired
    lateinit var codeableConceptRepository: CodeableConceptRepository

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    lateinit var jwtEncoder: JwtEncoder

    lateinit var panelConcept: CodeableConcept

    @BeforeEach
    fun setUpConcept() {
        panelConcept = TerminologyTestFixtures(codingRepository, codeableConceptRepository)
            .findOrCreateConcept(
                system = CanonicalCodeSystems.LOINC,
                code = "24323-8",
                display = "Comprehensive metabolic panel",
            )
    }

    @Test
    fun `attaching a result creates the report completes the order and records provenance`() {
        val correlationId = "result-attach-${UUID.randomUUID()}"
        val member = createMember(MembershipRole.CLINICIAN, "user/*.read user/*.write")
        val patient = createPatient(member.organization)
        val order = createOrder(member.organization, patient)
        val observation = createObservation(member.organization, patient)
        val scope = TenantScope(member.organization.id)

        val response = mockMvc.post("/api/v1/orders/${order.id.value}/results") {
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "codeConceptId": "${panelConcept.id.value}",
                  "resultObservationIds": ["${observation.id.value}"],
                  "conclusionText": "All values within normal limits."
                }
            """.trimIndent()
            header("Authorization", "Bearer ${member.token}")
            header("X-Correlation-Id", correlationId)
        }.andExpect {
            status { isCreated() }
            jsonPath("$.orderId") { value(order.id.value.toString()) }
            jsonPath("$.patientId") { value(patient.id.value.toString()) }
            jsonPath("$.status") { value("final") }
            jsonPath("$.conclusionText") { value("All values within normal limits.") }
            jsonPath("$.resultObservationIds[0]") { value(observation.id.value.toString()) }
        }.andReturn().response.contentAsString
        val reportId = UUID.fromString(Regex("\"id\":\"([0-9a-f-]+)\"").find(response)!!.groupValues[1])

        // order auto-completed with revision + provenance
        val completedOrder = orderRepository.findById(scope, order.id)!!
        assertEquals(OrderStatus.COMPLETED, completedOrder.status)
        assertEquals(2, completedOrder.version)
        val orderEvents = provenanceRepository.findByTarget(scope, "ORDER", order.id.value)
        assertEquals(ProvenanceActivity.UPDATED, orderEvents.last().activity)
        assertEquals(1, orderEvents.last().priorResourceVersion)

        // report provenance
        val reportEvents = provenanceRepository.findByTarget(scope, "DIAGNOSTIC_REPORT", reportId)
        assertEquals(1, reportEvents.size)
        assertEquals(ProvenanceActivity.CREATED, reportEvents[0].activity)

        // audit
        val audit = auditRow(correlationId)
        assertEquals("DIAGNOSTIC_REPORT", audit.resourceType)
        assertEquals("CREATE", audit.operation)
        assertEquals("SUCCESS", audit.outcome)
        assertEquals(patient.id.value.toString(), audit.patientId)
        assertEquals("policy-spine-v20", audit.policyVersion)
    }

    @Test
    fun `attaching results fails for non active orders cross tenant orders and foreign observations`() {
        val member = createMember(MembershipRole.CLINICIAN, "user/*.read user/*.write")
        val patient = createPatient(member.organization)
        val otherPatient = createPatient(member.organization)
        val order = createOrder(member.organization, patient)
        val observation = createObservation(member.organization, patient)
        val foreignObservation = createObservation(member.organization, otherPatient)

        // observation belonging to a different patient
        mockMvc.post("/api/v1/orders/${order.id.value}/results") {
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "codeConceptId": "${panelConcept.id.value}",
                  "resultObservationIds": ["${foreignObservation.id.value}"]
                }
            """.trimIndent()
            header("Authorization", "Bearer ${member.token}")
        }.andExpect {
            status { isBadRequest() }
        }

        // empty results
        mockMvc.post("/api/v1/orders/${order.id.value}/results") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"codeConceptId":"${panelConcept.id.value}","resultObservationIds":[]}"""
            header("Authorization", "Bearer ${member.token}")
        }.andExpect {
            status { isBadRequest() }
        }

        // cross-tenant order
        val outsider = createMember(MembershipRole.CLINICIAN, "user/*.read user/*.write")
        mockMvc.post("/api/v1/orders/${order.id.value}/results") {
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "codeConceptId": "${panelConcept.id.value}",
                  "resultObservationIds": ["${observation.id.value}"]
                }
            """.trimIndent()
            header("Authorization", "Bearer ${outsider.token}")
        }.andExpect {
            status { isNotFound() }
        }

        // attach successfully, then a second attach hits a completed order -> 422
        mockMvc.post("/api/v1/orders/${order.id.value}/results") {
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "codeConceptId": "${panelConcept.id.value}",
                  "resultObservationIds": ["${observation.id.value}"]
                }
            """.trimIndent()
            header("Authorization", "Bearer ${member.token}")
        }.andExpect {
            status { isCreated() }
        }
        mockMvc.post("/api/v1/orders/${order.id.value}/results") {
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "codeConceptId": "${panelConcept.id.value}",
                  "resultObservationIds": ["${observation.id.value}"]
                }
            """.trimIndent()
            header("Authorization", "Bearer ${member.token}")
        }.andExpect {
            status { isUnprocessableEntity() }
        }

        // nothing extra was written for the failed attempts
        assertEquals(1, diagnosticReportRepository.findByPatient(TenantScope(member.organization.id), patient.id).size)
    }

    @Test
    fun `staff cannot attach or read reports and reads are audited for clinicians`() {
        val readCorrelationId = "report-read-${UUID.randomUUID()}"
        val member = createMember(MembershipRole.CLINICIAN, "user/*.read user/*.write")
        val staff = createMember(MembershipRole.STAFF, "user/*.read user/*.write")
        val patient = createPatient(member.organization)
        val order = createOrder(member.organization, patient)
        val observation = createObservation(member.organization, patient)
        val report = diagnosticReportRepository.create(
            DiagnosticReportCreateCommand(
                organizationId = member.organization.id,
                patientId = patient.id,
                orderId = order.id,
                codeConceptId = panelConcept.id,
                resultObservationIds = listOf(observation.id),
            ),
        )

        mockMvc.get("/api/v1/diagnostic-reports/${report.id.value}") {
            header("Authorization", "Bearer ${member.token}")
            header("X-Correlation-Id", readCorrelationId)
        }.andExpect {
            status { isOk() }
            jsonPath("$.id") { value(report.id.value.toString()) }
            jsonPath("$.resultObservationIds.length()") { value(1) }
        }
        val audit = auditRow(readCorrelationId)
        assertEquals("READ", audit.operation)
        assertEquals(patient.id.value.toString(), audit.patientId)

        val staffPatient = createPatient(staff.organization)
        val staffOrder = createOrder(staff.organization, staffPatient)
        val staffObservation = createObservation(staff.organization, staffPatient)
        mockMvc.post("/api/v1/orders/${staffOrder.id.value}/results") {
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "codeConceptId": "${panelConcept.id.value}",
                  "resultObservationIds": ["${staffObservation.id.value}"]
                }
            """.trimIndent()
            header("Authorization", "Bearer ${staff.token}")
        }.andExpect {
            status { isForbidden() }
        }

        // patient report list
        mockMvc.get("/api/v1/patients/${patient.id.value}/diagnostic-reports") {
            header("Authorization", "Bearer ${member.token}")
        }.andExpect {
            status { isOk() }
            jsonPath("$.diagnosticReports.length()") { value(1) }
        }
    }

    private fun createOrganization(): Organization {
        val suffix = UUID.randomUUID()
        return organizationRepository.create(
            slug = "report-org-$suffix",
            displayName = "Report Org $suffix",
        )
    }

    private fun createMember(
        role: MembershipRole,
        scopes: String,
    ): ReportMemberFixture {
        val suffix = UUID.randomUUID()
        val organization = createOrganization()
        val user = userRepository.create(
            externalSubject = "report-user-$suffix",
            email = "report-user-$suffix@example.test",
            displayName = "Report User $suffix",
        )
        val membership = membershipRepository.create(
            organizationId = organization.id,
            userId = user.id,
        )
        membershipRepository.addRole(membership.id, role)

        return ReportMemberFixture(
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

    private fun createOrder(
        organization: Organization,
        patient: Patient,
    ): Order =
        orderRepository.create(
            OrderCreateCommand(
                organizationId = organization.id,
                patientId = patient.id,
                codeConceptId = panelConcept.id,
            ),
        )

    private fun createObservation(
        organization: Organization,
        patient: Patient,
    ): Observation =
        observationRepository.create(
            ObservationCreateCommand(
                organizationId = organization.id,
                patientId = patient.id,
                category = ObservationCategory.LABORATORY,
                codeConceptId = panelConcept.id,
                value = ObservationValue.Quantity(BigDecimal("4.5"), "mmol/L"),
                effectiveAt = Instant.parse("2026-06-01T09:30:00Z"),
            ),
        )

    private fun auditCount(correlationId: String): Int =
        jdbcTemplate.queryForObject(
            "select count(*) from audit_events where correlation_id = ?",
            Int::class.java,
            correlationId,
        )!!

    private fun auditRow(correlationId: String): ReportAuditRow {
        assertEquals(1, auditCount(correlationId), "expected exactly one audit row for $correlationId")
        return jdbcTemplate.queryForObject(
            """
            select
              patient_id::text,
              resource_type,
              operation,
              outcome,
              policy_version,
              policy_reason_code
            from audit_events
            where correlation_id = ?
            """.trimIndent(),
            { rs, _ ->
                ReportAuditRow(
                    patientId = rs.getString("patient_id"),
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

data class ReportMemberFixture(
    val organization: Organization,
    val user: User,
    val token: String,
)

data class ReportAuditRow(
    val patientId: String?,
    val resourceType: String,
    val operation: String,
    val outcome: String,
    val policyVersion: String?,
    val policyReasonCode: String?,
)
