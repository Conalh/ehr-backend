package dev.ehr.fhir

import dev.ehr.condition.Condition
import dev.ehr.condition.ConditionCreateCommand
import dev.ehr.condition.ConditionRepository
import dev.ehr.identity.MembershipRepository
import dev.ehr.identity.MembershipRole
import dev.ehr.identity.Organization
import dev.ehr.identity.OrganizationRepository
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
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import java.time.LocalDate
import java.util.UUID

@AutoConfigureMockMvc
@Import(DevJwtTestConfiguration::class)
class ConditionFhirApiIntegrationTest : PostgresIntegrationTest() {
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
    fun `fhir condition endpoints reject unauthenticated requests without audit`() {
        val correlationId = "fhir-cond-unauth-${UUID.randomUUID()}"

        mockMvc.get("/fhir/r4/Condition/${UUID.randomUUID()}") {
            header("X-Correlation-Id", correlationId)
        }.andExpect {
            status { isUnauthorized() }
        }

        assertEquals(0, auditCount(correlationId))
    }

    @Test
    fun `clinician can read a fhir condition and the read is audited`() {
        val correlationId = "fhir-cond-read-${UUID.randomUUID()}"
        val member = createMember(MembershipRole.CLINICIAN, "user/Condition.read")
        val patient = createPatient(member.organization)
        val condition = createCondition(member.organization, patient, onsetDate = LocalDate.of(2026, 1, 15))

        mockMvc.get("/fhir/r4/Condition/${condition.id.value}") {
            header("Authorization", "Bearer ${member.token}")
            header("X-Correlation-Id", correlationId)
        }.andExpect {
            status { isOk() }
            content { contentTypeCompatibleWith("application/fhir+json") }
            jsonPath("$.resourceType") { value("Condition") }
            jsonPath("$.id") { value(condition.id.value.toString()) }
            jsonPath("$.clinicalStatus.coding[0].code") { value("active") }
            jsonPath("$.verificationStatus.coding[0].code") { value("confirmed") }
            jsonPath("$.code.coding[0].system") { value("http://snomed.info/sct") }
            jsonPath("$.code.coding[0].code") { value("38341003") }
            jsonPath("$.code.text") { value("Hypertensive disorder") }
            jsonPath("$.subject.reference") { value("Patient/${patient.id.value}") }
            jsonPath("$.onsetDateTime") { value("2026-01-15") }
        }

        val audit = auditRow(correlationId)
        assertEquals("CONDITION", audit.resourceType)
        assertEquals("READ", audit.operation)
        assertEquals("SUCCESS", audit.outcome)
        assertEquals(patient.id.value.toString(), audit.patientId)
    }

    @Test
    fun `cross organization fhir condition read returns operation outcome not found`() {
        val correlationId = "fhir-cond-cross-${UUID.randomUUID()}"
        val member = createMember(MembershipRole.CLINICIAN, "user/Condition.read")
        val otherOrganization = createOrganization()
        val otherPatient = createPatient(otherOrganization)
        val otherCondition = createCondition(otherOrganization, otherPatient)

        mockMvc.get("/fhir/r4/Condition/${otherCondition.id.value}") {
            header("Authorization", "Bearer ${member.token}")
            header("X-Correlation-Id", correlationId)
        }.andExpect {
            status { isNotFound() }
            jsonPath("$.resourceType") { value("OperationOutcome") }
            jsonPath("$.issue[0].code") { value("not-found") }
        }

        val audit = auditRow(correlationId)
        assertEquals("READ", audit.operation)
        assertEquals("FAILURE", audit.outcome)
    }

    @Test
    fun `non uuid fhir condition id returns operation outcome not found without audit`() {
        val correlationId = "fhir-cond-badid-${UUID.randomUUID()}"
        val member = createMember(MembershipRole.CLINICIAN, "user/Condition.read")

        mockMvc.get("/fhir/r4/Condition/not-a-uuid") {
            header("Authorization", "Bearer ${member.token}")
            header("X-Correlation-Id", correlationId)
        }.andExpect {
            status { isNotFound() }
            jsonPath("$.resourceType") { value("OperationOutcome") }
        }

        assertEquals(0, auditCount(correlationId))
    }

    @Test
    fun `staff fhir condition read returns operation outcome forbidden`() {
        val correlationId = "fhir-cond-staff-${UUID.randomUUID()}"
        val member = createMember(MembershipRole.STAFF, "user/*.read")
        val patient = createPatient(member.organization)
        val condition = createCondition(member.organization, patient)

        mockMvc.get("/fhir/r4/Condition/${condition.id.value}") {
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
    fun `fhir condition compartment search returns bundle in both patient forms`() {
        val correlationId = "fhir-cond-search-${UUID.randomUUID()}"
        val member = createMember(MembershipRole.CLINICIAN, "user/Condition.read")
        val patient = createPatient(member.organization)
        val condition = createCondition(member.organization, patient)

        mockMvc.get("/fhir/r4/Condition") {
            param("patient", patient.id.value.toString())
            header("Authorization", "Bearer ${member.token}")
            header("X-Correlation-Id", correlationId)
        }.andExpect {
            status { isOk() }
            jsonPath("$.resourceType") { value("Bundle") }
            jsonPath("$.type") { value("searchset") }
            jsonPath("$.total") { value(1) }
            jsonPath("$.entry[0].resource.id") { value(condition.id.value.toString()) }
            jsonPath("$.entry[0].fullUrl") { value(org.hamcrest.Matchers.endsWith("/fhir/r4/Condition/${condition.id.value}")) }
            jsonPath("$.entry[0].search.mode") { value("match") }
        }

        val audit = auditRow(correlationId)
        assertEquals("SEARCH", audit.operation)
        assertEquals("SUCCESS", audit.outcome)
        assertEquals(patient.id.value.toString(), audit.patientId)

        mockMvc.get("/fhir/r4/Condition") {
            param("patient", "Patient/${patient.id.value}")
            header("Authorization", "Bearer ${member.token}")
        }.andExpect {
            status { isOk() }
            jsonPath("$.total") { value(1) }
        }
    }

    @Test
    fun `fhir condition search filters by category and clinical status`() {
        val member = createMember(MembershipRole.CLINICIAN, "user/Condition.read")
        val patient = createPatient(member.organization)
        createCondition(member.organization, patient)

        // Every condition is a problem-list item: matching category returns
        // all, any other an honest empty bundle.
        mockMvc.get("/fhir/r4/Condition") {
            param("patient", patient.id.value.toString())
            param("category", "problem-list-item")
            header("Authorization", "Bearer ${member.token}")
        }.andExpect {
            status { isOk() }
            jsonPath("$.total") { value(1) }
        }
        mockMvc.get("/fhir/r4/Condition") {
            param("patient", patient.id.value.toString())
            param("category", "health-concern")
            header("Authorization", "Bearer ${member.token}")
        }.andExpect {
            status { isOk() }
            jsonPath("$.total") { value(0) }
        }

        mockMvc.get("/fhir/r4/Condition") {
            param("patient", patient.id.value.toString())
            param("clinical-status", "active")
            header("Authorization", "Bearer ${member.token}")
        }.andExpect {
            status { isOk() }
            jsonPath("$.total") { value(1) }
        }
        mockMvc.get("/fhir/r4/Condition") {
            param("patient", patient.id.value.toString())
            param("clinical-status", "http://terminology.hl7.org/CodeSystem/condition-clinical|resolved")
            header("Authorization", "Bearer ${member.token}")
        }.andExpect {
            status { isOk() }
            jsonPath("$.total") { value(0) }
        }
    }

    @Test
    fun `fhir condition search for another organizations patient returns not found`() {
        val correlationId = "fhir-cond-search-cross-${UUID.randomUUID()}"
        val member = createMember(MembershipRole.CLINICIAN, "user/Condition.read")
        val otherOrganization = createOrganization()
        val otherPatient = createPatient(otherOrganization)
        createCondition(otherOrganization, otherPatient)

        mockMvc.get("/fhir/r4/Condition") {
            param("patient", otherPatient.id.value.toString())
            header("Authorization", "Bearer ${member.token}")
            header("X-Correlation-Id", correlationId)
        }.andExpect {
            status { isNotFound() }
            jsonPath("$.resourceType") { value("OperationOutcome") }
        }

        val audit = auditRow(correlationId)
        assertEquals("SEARCH", audit.operation)
        assertEquals("FAILURE", audit.outcome)
    }

    @Test
    fun `fhir condition search without usable patient parameter returns operation outcome invalid`() {
        val member = createMember(MembershipRole.CLINICIAN, "user/Condition.read")

        listOf(null, "not-a-uuid").forEach { patientParam ->
            mockMvc.get("/fhir/r4/Condition") {
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

    private fun createOrganization(): Organization {
        val suffix = UUID.randomUUID()
        return organizationRepository.create(
            slug = "fhir-cond-org-$suffix",
            displayName = "Fhir Cond Org $suffix",
        )
    }

    private fun createMember(
        role: MembershipRole,
        scopes: String,
    ): FhirMemberFixture {
        val suffix = UUID.randomUUID()
        val organization = createOrganization()
        val user = userRepository.create(
            externalSubject = "fhir-cond-user-$suffix",
            email = "fhir-cond-user-$suffix@example.test",
            displayName = "Fhir Cond User $suffix",
        )
        val membership = membershipRepository.create(
            organizationId = organization.id,
            userId = user.id,
        )
        membershipRepository.addRole(membership.id, role)

        return FhirMemberFixture(
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

    private fun createCondition(
        organization: Organization,
        patient: Patient,
        onsetDate: LocalDate? = null,
    ): Condition =
        conditionRepository.create(
            ConditionCreateCommand(
                organizationId = organization.id,
                patientId = patient.id,
                codeConceptId = codeConcept.id,
                onsetDate = onsetDate,
            ),
        )

    private fun auditCount(correlationId: String): Int =
        jdbcTemplate.queryForObject(
            "select count(*) from audit_events where correlation_id = ?",
            Int::class.java,
            correlationId,
        )!!

    private fun auditRow(correlationId: String): FhirEncounterAuditRow {
        assertEquals(1, auditCount(correlationId), "expected exactly one audit row for $correlationId")
        return jdbcTemplate.queryForObject(
            """
            select
              patient_id::text,
              resource_type,
              resource_id::text,
              operation,
              outcome,
              policy_reason_code
            from audit_events
            where correlation_id = ?
            """.trimIndent(),
            { rs, _ ->
                FhirEncounterAuditRow(
                    patientId = rs.getString("patient_id"),
                    resourceType = rs.getString("resource_type"),
                    resourceId = rs.getString("resource_id"),
                    operation = rs.getString("operation"),
                    outcome = rs.getString("outcome"),
                    policyReasonCode = rs.getString("policy_reason_code"),
                )
            },
            correlationId,
        )!!
    }
}
