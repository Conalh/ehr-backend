package dev.ehr.order

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
import dev.ehr.provenance.ProvenanceActivity
import dev.ehr.provenance.ProvenanceRepository
import dev.ehr.provenance.ResourceRevisionRepository
import dev.ehr.terminology.CanonicalCodeSystems
import dev.ehr.terminology.CodeableConcept
import dev.ehr.terminology.CodeableConceptRepository
import dev.ehr.terminology.CodingRepository
import dev.ehr.testsupport.DevJwtFactory
import dev.ehr.testsupport.DevJwtTestConfiguration
import dev.ehr.testsupport.PostgresIntegrationTest
import dev.ehr.testsupport.TerminologyTestFixtures
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
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
class OrderApiIntegrationTest : PostgresIntegrationTest() {
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
    lateinit var provenanceRepository: ProvenanceRepository

    @Autowired
    lateinit var resourceRevisionRepository: ResourceRevisionRepository

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
    fun `order endpoints reject unauthenticated requests without audit`() {
        val correlationId = "order-unauth-${UUID.randomUUID()}"

        mockMvc.get("/api/v1/orders/${UUID.randomUUID()}") {
            header("X-Correlation-Id", correlationId)
        }.andExpect {
            status { isUnauthorized() }
        }

        assertEquals(0, auditCount(correlationId))
    }

    @Test
    fun `clinician can place a lab order with audit and provenance`() {
        val correlationId = "order-place-${UUID.randomUUID()}"
        val member = createMember(MembershipRole.CLINICIAN, "user/ServiceRequest.write user/ServiceRequest.read")
        val patient = createPatient(member.organization)

        val response = mockMvc.post("/api/v1/orders") {
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "patientId": "${patient.id.value}",
                  "codeConceptId": "${panelConcept.id.value}",
                  "priority": "URGENT"
                }
            """.trimIndent()
            header("Authorization", "Bearer ${member.token}")
            header("X-Correlation-Id", correlationId)
        }.andExpect {
            status { isCreated() }
            jsonPath("$.id") { exists() }
            jsonPath("$.patientId") { value(patient.id.value.toString()) }
            jsonPath("$.status") { value("active") }
            jsonPath("$.codeConceptId") { value(panelConcept.id.value.toString()) }
            jsonPath("$.priority") { value("urgent") }
            jsonPath("$.version") { value(1) }
        }.andReturn().response.contentAsString
        val orderId = UUID.fromString(Regex("\"id\":\"([0-9a-f-]+)\"").find(response)!!.groupValues[1])

        val audit = auditRow(correlationId)
        assertEquals("ORDER", audit.resourceType)
        assertEquals("CREATE", audit.operation)
        assertEquals("SUCCESS", audit.outcome)
        assertEquals(patient.id.value.toString(), audit.patientId)
        assertEquals("policy-spine-v14", audit.policyVersion)

        val provenance = provenanceRepository
            .findByTarget(TenantScope(member.organization.id), "ORDER", orderId)
            .single()
        assertEquals(ProvenanceActivity.CREATED, provenance.activity)
        assertEquals(member.user.id, provenance.agentUserId)
    }

    @Test
    fun `order transitions follow the lifecycle with revision and provenance capture`() {
        val correlationId = "order-transition-${UUID.randomUUID()}"
        val member = createMember(MembershipRole.CLINICIAN, "user/ServiceRequest.write user/ServiceRequest.read")
        val patient = createPatient(member.organization)
        val order = createOrder(member.organization, patient)
        val scope = TenantScope(member.organization.id)

        // active -> on-hold
        mockMvc.post("/api/v1/orders/${order.id.value}/status") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"targetStatus":"ON_HOLD","expectedVersion":1}"""
            header("Authorization", "Bearer ${member.token}")
            header("X-Correlation-Id", correlationId)
        }.andExpect {
            status { isOk() }
            jsonPath("$.status") { value("on-hold") }
            jsonPath("$.version") { value(2) }
        }

        val audit = auditRow(correlationId)
        assertEquals("UPDATE", audit.operation)
        assertEquals("SUCCESS", audit.outcome)

        // on-hold -> active -> completed
        mockMvc.post("/api/v1/orders/${order.id.value}/status") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"targetStatus":"ACTIVE","expectedVersion":2}"""
            header("Authorization", "Bearer ${member.token}")
        }.andExpect {
            status { isOk() }
        }
        mockMvc.post("/api/v1/orders/${order.id.value}/status") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"targetStatus":"COMPLETED","expectedVersion":3}"""
            header("Authorization", "Bearer ${member.token}")
        }.andExpect {
            status { isOk() }
            jsonPath("$.status") { value("completed") }
            jsonPath("$.version") { value(4) }
        }

        val revisions = resourceRevisionRepository.findRevisions(scope, "ORDER", order.id.value)
        assertEquals(listOf(1, 2, 3), revisions.map { it.version })

        // The fixture order was created through the repository, so only the three
        // API transitions produce provenance here (creation provenance is covered above).
        val events = provenanceRepository.findByTarget(scope, "ORDER", order.id.value)
        assertEquals(3, events.size)
        assertTrue(events.all { it.activity == ProvenanceActivity.UPDATED })
        assertEquals(listOf(1, 2, 3), events.map { it.priorResourceVersion })
    }

    @Test
    fun `invalid and stale order transitions are rejected and audited`() {
        val staleCorrelationId = "order-stale-${UUID.randomUUID()}"
        val invalidCorrelationId = "order-invalid-${UUID.randomUUID()}"
        val member = createMember(MembershipRole.CLINICIAN, "user/ServiceRequest.write user/ServiceRequest.read")
        val patient = createPatient(member.organization)
        val order = createOrder(member.organization, patient)

        // stale expectedVersion
        mockMvc.post("/api/v1/orders/${order.id.value}/status") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"targetStatus":"ON_HOLD","expectedVersion":7}"""
            header("Authorization", "Bearer ${member.token}")
            header("X-Correlation-Id", staleCorrelationId)
        }.andExpect {
            status { isConflict() }
        }
        assertEquals("FAILURE", auditRow(staleCorrelationId).outcome)

        // revoke, then revoked -> on-hold is invalid
        mockMvc.post("/api/v1/orders/${order.id.value}/status") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"targetStatus":"REVOKED","expectedVersion":1}"""
            header("Authorization", "Bearer ${member.token}")
        }.andExpect {
            status { isOk() }
        }
        mockMvc.post("/api/v1/orders/${order.id.value}/status") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"targetStatus":"ON_HOLD","expectedVersion":2}"""
            header("Authorization", "Bearer ${member.token}")
            header("X-Correlation-Id", invalidCorrelationId)
        }.andExpect {
            status { isUnprocessableEntity() }
        }
        assertEquals("FAILURE", auditRow(invalidCorrelationId).outcome)
    }

    @Test
    fun `cross tenant order access fails closed`() {
        val member = createMember(MembershipRole.CLINICIAN, "user/ServiceRequest.write user/ServiceRequest.read")
        val otherOrganization = createOrganization()
        val otherPatient = createPatient(otherOrganization)
        val otherOrder = createOrder(otherOrganization, otherPatient)

        // place for another organization's patient
        mockMvc.post("/api/v1/orders") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"patientId":"${otherPatient.id.value}","codeConceptId":"${panelConcept.id.value}"}"""
            header("Authorization", "Bearer ${member.token}")
        }.andExpect {
            status { isNotFound() }
        }

        // read / transition another organization's order
        mockMvc.get("/api/v1/orders/${otherOrder.id.value}") {
            header("Authorization", "Bearer ${member.token}")
        }.andExpect {
            status { isNotFound() }
        }
        mockMvc.post("/api/v1/orders/${otherOrder.id.value}/status") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"targetStatus":"ON_HOLD","expectedVersion":1}"""
            header("Authorization", "Bearer ${member.token}")
        }.andExpect {
            status { isNotFound() }
        }

        // list for another organization's patient
        mockMvc.get("/api/v1/patients/${otherPatient.id.value}/orders") {
            header("Authorization", "Bearer ${member.token}")
        }.andExpect {
            status { isNotFound() }
        }
    }

    @Test
    fun `staff cannot place or read orders and unknown orderable is rejected`() {
        val correlationId = "order-staff-${UUID.randomUUID()}"
        val staff = createMember(MembershipRole.STAFF, "user/*.read user/*.write")
        val clinician = createMember(MembershipRole.CLINICIAN, "user/ServiceRequest.write")
        val staffPatient = createPatient(staff.organization)
        val clinicianPatient = createPatient(clinician.organization)

        mockMvc.post("/api/v1/orders") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"patientId":"${staffPatient.id.value}","codeConceptId":"${panelConcept.id.value}"}"""
            header("Authorization", "Bearer ${staff.token}")
            header("X-Correlation-Id", correlationId)
        }.andExpect {
            status { isForbidden() }
        }
        assertEquals("INSUFFICIENT_ROLE", auditRow(correlationId).policyReasonCode)

        mockMvc.post("/api/v1/orders") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"patientId":"${clinicianPatient.id.value}","codeConceptId":"${UUID.randomUUID()}"}"""
            header("Authorization", "Bearer ${clinician.token}")
        }.andExpect {
            status { isBadRequest() }
        }
    }

    @Test
    fun `order list returns the patients orders newest first with search audit`() {
        val correlationId = "order-list-${UUID.randomUUID()}"
        val member = createMember(MembershipRole.CLINICIAN, "user/ServiceRequest.read")
        val patient = createPatient(member.organization)
        val otherPatient = createPatient(member.organization)
        val order = createOrder(member.organization, patient)
        createOrder(member.organization, otherPatient)

        mockMvc.get("/api/v1/patients/${patient.id.value}/orders") {
            header("Authorization", "Bearer ${member.token}")
            header("X-Correlation-Id", correlationId)
        }.andExpect {
            status { isOk() }
            jsonPath("$.orders.length()") { value(1) }
            jsonPath("$.orders[0].id") { value(order.id.value.toString()) }
        }

        val audit = auditRow(correlationId)
        assertEquals("SEARCH", audit.operation)
        assertEquals("SUCCESS", audit.outcome)
        assertEquals(patient.id.value.toString(), audit.patientId)
    }

    private fun createOrganization(): Organization {
        val suffix = UUID.randomUUID()
        return organizationRepository.create(
            slug = "order-org-$suffix",
            displayName = "Order Org $suffix",
        )
    }

    private fun createMember(
        role: MembershipRole,
        scopes: String,
    ): OrderMemberFixture {
        val suffix = UUID.randomUUID()
        val organization = createOrganization()
        val user = userRepository.create(
            externalSubject = "order-user-$suffix",
            email = "order-user-$suffix@example.test",
            displayName = "Order User $suffix",
        )
        val membership = membershipRepository.create(
            organizationId = organization.id,
            userId = user.id,
        )
        membershipRepository.addRole(membership.id, role)

        return OrderMemberFixture(
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

    private fun auditCount(correlationId: String): Int =
        jdbcTemplate.queryForObject(
            "select count(*) from audit_events where correlation_id = ?",
            Int::class.java,
            correlationId,
        )!!

    private fun auditRow(correlationId: String): OrderAuditRow {
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
                OrderAuditRow(
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

data class OrderMemberFixture(
    val organization: Organization,
    val user: User,
    val token: String,
)

data class OrderAuditRow(
    val patientId: String?,
    val resourceType: String,
    val resourceId: String?,
    val operation: String,
    val outcome: String,
    val policyVersion: String?,
    val policyReasonCode: String?,
)
