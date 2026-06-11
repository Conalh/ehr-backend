package dev.ehr.condition

import dev.ehr.encounter.Encounter
import dev.ehr.encounter.EncounterCreateCommand
import dev.ehr.encounter.EncounterRepository
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
import dev.ehr.testsupport.EncounterTestFixtures
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
import java.time.Instant
import java.util.UUID

@AutoConfigureMockMvc
@Import(DevJwtTestConfiguration::class)
class ConditionApiIntegrationTest : PostgresIntegrationTest() {
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
    lateinit var encounterRepository: EncounterRepository

    @Autowired
    lateinit var conditionRepository: ConditionRepository

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
                code = "38341003",
                display = "Hypertensive disorder",
            )
    }

    @Test
    fun `condition endpoints reject unauthenticated requests without audit`() {
        val correlationId = "condition-unauth-${UUID.randomUUID()}"

        mockMvc.get("/api/v1/conditions/${UUID.randomUUID()}") {
            header("X-Correlation-Id", correlationId)
        }.andExpect {
            status { isUnauthorized() }
        }

        assertEquals(0, auditCount(correlationId))
    }

    @Test
    fun `clinician can record a condition with encounter link and the create is audited`() {
        val correlationId = "condition-record-${UUID.randomUUID()}"
        val member = createMember(MembershipRole.CLINICIAN, "user/Condition.write user/Condition.read")
        val patient = createPatient(member.organization)
        val encounter = createEncounter(member.organization, patient)

        mockMvc.post("/api/v1/patients/${patient.id.value}/conditions") {
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "codeConceptId": "${codeConcept.id.value}",
                  "encounterId": "${encounter.id.value}",
                  "clinicalStatus": "ACTIVE",
                  "verificationStatus": "CONFIRMED",
                  "onsetDate": "2026-01-15"
                }
            """.trimIndent()
            header("Authorization", "Bearer ${member.token}")
            header("X-Correlation-Id", correlationId)
        }.andExpect {
            status { isCreated() }
            jsonPath("$.id") { exists() }
            jsonPath("$.patientId") { value(patient.id.value.toString()) }
            jsonPath("$.encounterId") { value(encounter.id.value.toString()) }
            jsonPath("$.clinicalStatus") { value("active") }
            jsonPath("$.verificationStatus") { value("confirmed") }
            jsonPath("$.codeConceptId") { value(codeConcept.id.value.toString()) }
            jsonPath("$.onsetDate") { value("2026-01-15") }
            jsonPath("$.version") { value(1) }
        }

        val audit = auditRow(correlationId)
        assertEquals("CONDITION", audit.resourceType)
        assertEquals("CREATE", audit.operation)
        assertEquals("SUCCESS", audit.outcome)
        assertEquals(patient.id.value.toString(), audit.patientId)
        assertEquals("policy-spine-v16", audit.policyVersion)
    }

    @Test
    fun `staff cannot read or record conditions`() {
        val readCorrelationId = "condition-staff-read-${UUID.randomUUID()}"
        val writeCorrelationId = "condition-staff-write-${UUID.randomUUID()}"
        val member = createMember(MembershipRole.STAFF, "user/*.read user/*.write")
        val patient = createPatient(member.organization)
        val condition = createCondition(member.organization, patient)

        mockMvc.get("/api/v1/conditions/${condition.id.value}") {
            header("Authorization", "Bearer ${member.token}")
            header("X-Correlation-Id", readCorrelationId)
        }.andExpect {
            status { isForbidden() }
        }

        val readAudit = auditRow(readCorrelationId)
        assertEquals("AUTHORIZATION_DENIED", readAudit.operation)
        assertEquals("INSUFFICIENT_ROLE", readAudit.policyReasonCode)

        mockMvc.post("/api/v1/patients/${patient.id.value}/conditions") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"codeConceptId":"${codeConcept.id.value}"}"""
            header("Authorization", "Bearer ${member.token}")
            header("X-Correlation-Id", writeCorrelationId)
        }.andExpect {
            status { isForbidden() }
        }

        val writeAudit = auditRow(writeCorrelationId)
        assertEquals("AUTHORIZATION_DENIED", writeAudit.operation)
        assertEquals("INSUFFICIENT_ROLE", writeAudit.policyReasonCode)
    }

    @Test
    fun `clinician can read a condition and the read is audited`() {
        val correlationId = "condition-read-${UUID.randomUUID()}"
        val member = createMember(MembershipRole.CLINICIAN, "user/Condition.read")
        val patient = createPatient(member.organization)
        val condition = createCondition(member.organization, patient)

        mockMvc.get("/api/v1/conditions/${condition.id.value}") {
            header("Authorization", "Bearer ${member.token}")
            header("X-Correlation-Id", correlationId)
        }.andExpect {
            status { isOk() }
            jsonPath("$.id") { value(condition.id.value.toString()) }
            jsonPath("$.clinicalStatus") { value("active") }
        }

        val audit = auditRow(correlationId)
        assertEquals("READ", audit.operation)
        assertEquals("SUCCESS", audit.outcome)
        assertEquals(condition.id.value.toString(), audit.resourceId)
        assertEquals(patient.id.value.toString(), audit.patientId)
    }

    @Test
    fun `cross organization condition read returns 404 and audits a failed read`() {
        val correlationId = "condition-read-cross-${UUID.randomUUID()}"
        val member = createMember(MembershipRole.CLINICIAN, "user/Condition.read")
        val otherOrganization = createOrganization()
        val otherPatient = createPatient(otherOrganization)
        val otherCondition = createCondition(otherOrganization, otherPatient)

        mockMvc.get("/api/v1/conditions/${otherCondition.id.value}") {
            header("Authorization", "Bearer ${member.token}")
            header("X-Correlation-Id", correlationId)
        }.andExpect {
            status { isNotFound() }
        }

        val audit = auditRow(correlationId)
        assertEquals("READ", audit.operation)
        assertEquals("FAILURE", audit.outcome)
        assertEquals(null, audit.patientId)
    }

    @Test
    fun `recording a condition for another organizations patient returns 404`() {
        val member = createMember(MembershipRole.CLINICIAN, "user/Condition.write")
        val otherOrganization = createOrganization()
        val otherPatient = createPatient(otherOrganization)

        mockMvc.post("/api/v1/patients/${otherPatient.id.value}/conditions") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"codeConceptId":"${codeConcept.id.value}"}"""
            header("Authorization", "Bearer ${member.token}")
        }.andExpect {
            status { isNotFound() }
        }

        assertEquals(0, conditionCount(otherOrganization))
    }

    @Test
    fun `recording a condition with another organizations encounter returns 404`() {
        val member = createMember(MembershipRole.CLINICIAN, "user/Condition.write")
        val patient = createPatient(member.organization)
        val otherOrganization = createOrganization()
        val otherPatient = createPatient(otherOrganization)
        val otherEncounter = createEncounter(otherOrganization, otherPatient)

        mockMvc.post("/api/v1/patients/${patient.id.value}/conditions") {
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "codeConceptId": "${codeConcept.id.value}",
                  "encounterId": "${otherEncounter.id.value}"
                }
            """.trimIndent()
            header("Authorization", "Bearer ${member.token}")
        }.andExpect {
            status { isNotFound() }
        }

        assertEquals(0, conditionCount(member.organization))
    }

    @Test
    fun `recording a condition with unknown code concept returns 400`() {
        val member = createMember(MembershipRole.CLINICIAN, "user/Condition.write")
        val patient = createPatient(member.organization)

        mockMvc.post("/api/v1/patients/${patient.id.value}/conditions") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"codeConceptId":"${UUID.randomUUID()}"}"""
            header("Authorization", "Bearer ${member.token}")
        }.andExpect {
            status { isBadRequest() }
        }

        assertEquals(0, conditionCount(member.organization))
    }

    @Test
    fun `recording a condition with abatement before onset returns 400`() {
        val member = createMember(MembershipRole.CLINICIAN, "user/Condition.write")
        val patient = createPatient(member.organization)

        mockMvc.post("/api/v1/patients/${patient.id.value}/conditions") {
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "codeConceptId": "${codeConcept.id.value}",
                  "onsetDate": "2026-05-01",
                  "abatementDate": "2026-01-01"
                }
            """.trimIndent()
            header("Authorization", "Bearer ${member.token}")
        }.andExpect {
            status { isBadRequest() }
        }

        assertEquals(0, conditionCount(member.organization))
    }

    @Test
    fun `problem list is audited as a search and excludes other patients`() {
        val correlationId = "condition-list-${UUID.randomUUID()}"
        val member = createMember(MembershipRole.CLINICIAN, "user/Condition.read")
        val patient = createPatient(member.organization)
        val otherPatient = createPatient(member.organization)
        val condition = createCondition(member.organization, patient)
        createCondition(member.organization, otherPatient)

        mockMvc.get("/api/v1/patients/${patient.id.value}/conditions") {
            header("Authorization", "Bearer ${member.token}")
            header("X-Correlation-Id", correlationId)
        }.andExpect {
            status { isOk() }
            jsonPath("$.conditions.length()") { value(1) }
            jsonPath("$.conditions[0].id") { value(condition.id.value.toString()) }
        }

        val audit = auditRow(correlationId)
        assertEquals("SEARCH", audit.operation)
        assertEquals("SUCCESS", audit.outcome)
        assertEquals(patient.id.value.toString(), audit.patientId)
    }

    @Test
    fun `clinician without condition scope is denied`() {
        val correlationId = "condition-scope-${UUID.randomUUID()}"
        val member = createMember(MembershipRole.CLINICIAN, "user/Encounter.read")
        val patient = createPatient(member.organization)
        val condition = createCondition(member.organization, patient)

        mockMvc.get("/api/v1/conditions/${condition.id.value}") {
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
            slug = "condition-api-org-$suffix",
            displayName = "Condition Api Org $suffix",
        )
    }

    private fun createMember(
        role: MembershipRole,
        scopes: String,
    ): ConditionMemberFixture {
        val suffix = UUID.randomUUID()
        val organization = createOrganization()
        val user = userRepository.create(
            externalSubject = "condition-api-user-$suffix",
            email = "condition-api-user-$suffix@example.test",
            displayName = "Condition Api User $suffix",
        )
        val membership = membershipRepository.create(
            organizationId = organization.id,
            userId = user.id,
        )
        membershipRepository.addRole(membership.id, role)

        return ConditionMemberFixture(
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

    private fun createEncounter(
        organization: Organization,
        patient: Patient,
    ): Encounter {
        val classConcept = EncounterTestFixtures(codingRepository, codeableConceptRepository)
            .createEncounterClassConcept()
        return encounterRepository.create(
            EncounterCreateCommand(
                organizationId = organization.id,
                patientId = patient.id,
                classConceptId = classConcept.id,
                periodStart = Instant.parse("2026-06-01T09:00:00Z"),
            ),
        )
    }

    private fun createCondition(
        organization: Organization,
        patient: Patient,
    ): Condition =
        conditionRepository.create(
            ConditionCreateCommand(
                organizationId = organization.id,
                patientId = patient.id,
                codeConceptId = codeConcept.id,
            ),
        )

    private fun conditionCount(organization: Organization): Int =
        jdbcTemplate.queryForObject(
            "select count(*) from conditions where organization_id = ?",
            Int::class.java,
            organization.id.value,
        )!!

    private fun auditCount(correlationId: String): Int =
        jdbcTemplate.queryForObject(
            "select count(*) from audit_events where correlation_id = ?",
            Int::class.java,
            correlationId,
        )!!

    private fun auditRow(correlationId: String): ConditionAuditRow {
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
                ConditionAuditRow(
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

data class ConditionMemberFixture(
    val organization: Organization,
    val user: User,
    val token: String,
)

data class ConditionAuditRow(
    val patientId: String?,
    val resourceType: String,
    val resourceId: String?,
    val operation: String,
    val outcome: String,
    val policyVersion: String?,
    val policyReasonCode: String?,
)
