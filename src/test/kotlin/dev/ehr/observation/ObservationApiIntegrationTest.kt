package dev.ehr.observation

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
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

@AutoConfigureMockMvc
@Import(DevJwtTestConfiguration::class)
class ObservationApiIntegrationTest : PostgresIntegrationTest() {
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
    lateinit var observationRepository: ObservationRepository

    @Autowired
    lateinit var codingRepository: CodingRepository

    @Autowired
    lateinit var codeableConceptRepository: CodeableConceptRepository

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    lateinit var jwtEncoder: JwtEncoder

    lateinit var heartRateConcept: CodeableConcept
    lateinit var codedResultConcept: CodeableConcept

    private val effective = "2026-06-01T09:30:00Z"

    @BeforeEach
    fun setUpConcepts() {
        val fixtures = TerminologyTestFixtures(codingRepository, codeableConceptRepository)
        heartRateConcept = fixtures.findOrCreateConcept(
            system = CanonicalCodeSystems.LOINC,
            code = "8867-4",
            display = "Heart rate",
        )
        codedResultConcept = fixtures.findOrCreateConcept(
            system = CanonicalCodeSystems.SNOMED_CT,
            code = "260385009",
            display = "Negative",
        )
    }

    @Test
    fun `observation endpoints reject unauthenticated requests without audit`() {
        val correlationId = "obs-unauth-${UUID.randomUUID()}"

        mockMvc.get("/api/v1/observations/${UUID.randomUUID()}") {
            header("X-Correlation-Id", correlationId)
        }.andExpect {
            status { isUnauthorized() }
        }

        assertEquals(0, auditCount(correlationId))
    }

    @Test
    fun `clinician can record a quantity observation and the create is audited`() {
        val correlationId = "obs-record-qty-${UUID.randomUUID()}"
        val member = createMember(MembershipRole.CLINICIAN, "user/Observation.write user/Observation.read")
        val patient = createPatient(member.organization)

        mockMvc.post("/api/v1/patients/${patient.id.value}/observations") {
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "codeConceptId": "${heartRateConcept.id.value}",
                  "category": "VITAL_SIGNS",
                  "effectiveAt": "$effective",
                  "valueQuantity": {"value": 72, "unit": "/min"}
                }
            """.trimIndent()
            header("Authorization", "Bearer ${member.token}")
            header("X-Correlation-Id", correlationId)
        }.andExpect {
            status { isCreated() }
            jsonPath("$.id") { exists() }
            jsonPath("$.patientId") { value(patient.id.value.toString()) }
            jsonPath("$.status") { value("final") }
            jsonPath("$.category") { value("vital-signs") }
            jsonPath("$.codeConceptId") { value(heartRateConcept.id.value.toString()) }
            jsonPath("$.value.quantity") { value(72) }
            jsonPath("$.value.unit") { value("/min") }
            jsonPath("$.effectiveAt") { value(effective) }
            jsonPath("$.version") { value(1) }
        }

        val audit = auditRow(correlationId)
        assertEquals("OBSERVATION", audit.resourceType)
        assertEquals("CREATE", audit.operation)
        assertEquals("SUCCESS", audit.outcome)
        assertEquals(patient.id.value.toString(), audit.patientId)
        assertEquals("policy-spine-v20", audit.policyVersion)
    }

    @Test
    fun `coded and text observation values round trip`() {
        val member = createMember(MembershipRole.CLINICIAN, "user/Observation.write user/Observation.read")
        val patient = createPatient(member.organization)

        mockMvc.post("/api/v1/patients/${patient.id.value}/observations") {
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "codeConceptId": "${heartRateConcept.id.value}",
                  "category": "LABORATORY",
                  "effectiveAt": "$effective",
                  "valueConceptId": "${codedResultConcept.id.value}"
                }
            """.trimIndent()
            header("Authorization", "Bearer ${member.token}")
        }.andExpect {
            status { isCreated() }
            jsonPath("$.category") { value("laboratory") }
            jsonPath("$.value.conceptId") { value(codedResultConcept.id.value.toString()) }
        }

        mockMvc.post("/api/v1/patients/${patient.id.value}/observations") {
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "codeConceptId": "${heartRateConcept.id.value}",
                  "category": "LABORATORY",
                  "effectiveAt": "$effective",
                  "valueText": "Trace protein"
                }
            """.trimIndent()
            header("Authorization", "Bearer ${member.token}")
        }.andExpect {
            status { isCreated() }
            jsonPath("$.value.text") { value("Trace protein") }
        }
    }

    @Test
    fun `recording an observation with no value or multiple values returns 400`() {
        val member = createMember(MembershipRole.CLINICIAN, "user/Observation.write")
        val patient = createPatient(member.organization)

        mockMvc.post("/api/v1/patients/${patient.id.value}/observations") {
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "codeConceptId": "${heartRateConcept.id.value}",
                  "category": "VITAL_SIGNS",
                  "effectiveAt": "$effective"
                }
            """.trimIndent()
            header("Authorization", "Bearer ${member.token}")
        }.andExpect {
            status { isBadRequest() }
        }

        mockMvc.post("/api/v1/patients/${patient.id.value}/observations") {
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "codeConceptId": "${heartRateConcept.id.value}",
                  "category": "VITAL_SIGNS",
                  "effectiveAt": "$effective",
                  "valueQuantity": {"value": 72, "unit": "/min"},
                  "valueText": "seventy-two"
                }
            """.trimIndent()
            header("Authorization", "Bearer ${member.token}")
        }.andExpect {
            status { isBadRequest() }
        }

        assertEquals(0, observationCount(member.organization))
    }

    @Test
    fun `staff cannot read or record observations`() {
        val correlationId = "obs-staff-${UUID.randomUUID()}"
        val member = createMember(MembershipRole.STAFF, "user/*.read user/*.write")
        val patient = createPatient(member.organization)
        val observation = createObservation(member.organization, patient)

        mockMvc.get("/api/v1/observations/${observation.id.value}") {
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
    fun `cross organization observation read returns 404 and audits a failed read`() {
        val correlationId = "obs-cross-${UUID.randomUUID()}"
        val member = createMember(MembershipRole.CLINICIAN, "user/Observation.read")
        val otherOrganization = createOrganization()
        val otherPatient = createPatient(otherOrganization)
        val otherObservation = createObservation(otherOrganization, otherPatient)

        mockMvc.get("/api/v1/observations/${otherObservation.id.value}") {
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
    fun `observation list filters by category and is audited as a search`() {
        val correlationId = "obs-list-${UUID.randomUUID()}"
        val member = createMember(MembershipRole.CLINICIAN, "user/Observation.read")
        val patient = createPatient(member.organization)
        val vitalSign = createObservation(member.organization, patient, category = ObservationCategory.VITAL_SIGNS)
        createObservation(member.organization, patient, category = ObservationCategory.LABORATORY)

        mockMvc.get("/api/v1/patients/${patient.id.value}/observations") {
            param("category", "VITAL_SIGNS")
            header("Authorization", "Bearer ${member.token}")
            header("X-Correlation-Id", correlationId)
        }.andExpect {
            status { isOk() }
            jsonPath("$.observations.length()") { value(1) }
            jsonPath("$.observations[0].id") { value(vitalSign.id.value.toString()) }
        }

        mockMvc.get("/api/v1/patients/${patient.id.value}/observations") {
            header("Authorization", "Bearer ${member.token}")
        }.andExpect {
            status { isOk() }
            jsonPath("$.observations.length()") { value(2) }
        }

        val audit = auditRow(correlationId)
        assertEquals("SEARCH", audit.operation)
        assertEquals("SUCCESS", audit.outcome)
        assertEquals(patient.id.value.toString(), audit.patientId)
    }

    @Test
    fun `clinician without observation scope is denied`() {
        val correlationId = "obs-scope-${UUID.randomUUID()}"
        val member = createMember(MembershipRole.CLINICIAN, "user/Condition.read")
        val patient = createPatient(member.organization)
        val observation = createObservation(member.organization, patient)

        mockMvc.get("/api/v1/observations/${observation.id.value}") {
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
            slug = "obs-api-org-$suffix",
            displayName = "Obs Api Org $suffix",
        )
    }

    private fun createMember(
        role: MembershipRole,
        scopes: String,
    ): ObservationMemberFixture {
        val suffix = UUID.randomUUID()
        val organization = createOrganization()
        val user = userRepository.create(
            externalSubject = "obs-api-user-$suffix",
            email = "obs-api-user-$suffix@example.test",
            displayName = "Obs Api User $suffix",
        )
        val membership = membershipRepository.create(
            organizationId = organization.id,
            userId = user.id,
        )
        membershipRepository.addRole(membership.id, role)

        return ObservationMemberFixture(
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

    private fun createObservation(
        organization: Organization,
        patient: Patient,
        category: ObservationCategory = ObservationCategory.VITAL_SIGNS,
    ): Observation =
        observationRepository.create(
            ObservationCreateCommand(
                organizationId = organization.id,
                patientId = patient.id,
                category = category,
                codeConceptId = heartRateConcept.id,
                value = ObservationValue.Quantity(BigDecimal("72"), "/min"),
                effectiveAt = Instant.parse(effective),
            ),
        )

    private fun observationCount(organization: Organization): Int =
        jdbcTemplate.queryForObject(
            "select count(*) from observations where organization_id = ?",
            Int::class.java,
            organization.id.value,
        )!!

    private fun auditCount(correlationId: String): Int =
        jdbcTemplate.queryForObject(
            "select count(*) from audit_events where correlation_id = ?",
            Int::class.java,
            correlationId,
        )!!

    private fun auditRow(correlationId: String): ObservationAuditRow {
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
                ObservationAuditRow(
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

data class ObservationMemberFixture(
    val organization: Organization,
    val user: User,
    val token: String,
)

data class ObservationAuditRow(
    val patientId: String?,
    val resourceType: String,
    val resourceId: String?,
    val operation: String,
    val outcome: String,
    val policyVersion: String?,
    val policyReasonCode: String?,
)
