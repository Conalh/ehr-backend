package dev.ehr.fhir

import dev.ehr.identity.MembershipRepository
import dev.ehr.identity.MembershipRole
import dev.ehr.identity.Organization
import dev.ehr.identity.OrganizationRepository
import dev.ehr.identity.UserRepository
import dev.ehr.medication.MedicationStatement
import dev.ehr.medication.MedicationStatementCreateCommand
import dev.ehr.medication.MedicationStatementRepository
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
class MedicationStatementFhirApiIntegrationTest : PostgresIntegrationTest() {
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
    fun `fhir medication statement endpoints reject unauthenticated requests without audit`() {
        val correlationId = "fhir-med-unauth-${UUID.randomUUID()}"

        mockMvc.get("/fhir/r4/MedicationStatement/${UUID.randomUUID()}") {
            header("X-Correlation-Id", correlationId)
        }.andExpect {
            status { isUnauthorized() }
        }

        assertEquals(0, auditCount(correlationId))
    }

    @Test
    fun `clinician can read a fhir medication statement and the read is audited`() {
        val correlationId = "fhir-med-read-${UUID.randomUUID()}"
        val member = createMember(MembershipRole.CLINICIAN, "user/MedicationStatement.read")
        val patient = createPatient(member.organization)
        val statement = createStatement(member.organization, patient)

        mockMvc.get("/fhir/r4/MedicationStatement/${statement.id.value}") {
            header("Authorization", "Bearer ${member.token}")
            header("X-Correlation-Id", correlationId)
        }.andExpect {
            status { isOk() }
            content { contentTypeCompatibleWith("application/fhir+json") }
            jsonPath("$.resourceType") { value("MedicationStatement") }
            jsonPath("$.id") { value(statement.id.value.toString()) }
            jsonPath("$.status") { value("active") }
            jsonPath("$.medicationCodeableConcept.coding[0].system") { value("http://www.nlm.nih.gov/research/umls/rxnorm") }
            jsonPath("$.medicationCodeableConcept.coding[0].code") { value("197361") }
            jsonPath("$.medicationCodeableConcept.text") { value("Lisinopril 10 MG Oral Tablet") }
            jsonPath("$.subject.reference") { value("Patient/${patient.id.value}") }
            jsonPath("$.effectivePeriod.start") { value("2026-01-01") }
            jsonPath("$.dosage[0].text") { value("10 mg orally once daily") }
            jsonPath("$.dateAsserted") { exists() }
        }

        val audit = auditRow(correlationId)
        assertEquals("MEDICATION", audit.resourceType)
        assertEquals("READ", audit.operation)
        assertEquals("SUCCESS", audit.outcome)
        assertEquals(patient.id.value.toString(), audit.patientId)
    }

    @Test
    fun `cross organization fhir medication statement read returns operation outcome not found`() {
        val member = createMember(MembershipRole.CLINICIAN, "user/MedicationStatement.read")
        val otherOrganization = createOrganization()
        val otherPatient = createPatient(otherOrganization)
        val otherStatement = createStatement(otherOrganization, otherPatient)

        mockMvc.get("/fhir/r4/MedicationStatement/${otherStatement.id.value}") {
            header("Authorization", "Bearer ${member.token}")
        }.andExpect {
            status { isNotFound() }
            jsonPath("$.resourceType") { value("OperationOutcome") }
            jsonPath("$.issue[0].code") { value("not-found") }
        }
    }

    @Test
    fun `staff fhir medication statement read returns operation outcome forbidden`() {
        val member = createMember(MembershipRole.STAFF, "user/*.read")
        val patient = createPatient(member.organization)
        val statement = createStatement(member.organization, patient)

        mockMvc.get("/fhir/r4/MedicationStatement/${statement.id.value}") {
            header("Authorization", "Bearer ${member.token}")
        }.andExpect {
            status { isForbidden() }
            jsonPath("$.resourceType") { value("OperationOutcome") }
            jsonPath("$.issue[0].code") { value("forbidden") }
        }
    }

    @Test
    fun `fhir medication statement compartment search returns bundle in both patient forms`() {
        val correlationId = "fhir-med-search-${UUID.randomUUID()}"
        val member = createMember(MembershipRole.CLINICIAN, "user/MedicationStatement.read")
        val patient = createPatient(member.organization)
        val statement = createStatement(member.organization, patient)

        mockMvc.get("/fhir/r4/MedicationStatement") {
            param("patient", patient.id.value.toString())
            header("Authorization", "Bearer ${member.token}")
            header("X-Correlation-Id", correlationId)
        }.andExpect {
            status { isOk() }
            jsonPath("$.resourceType") { value("Bundle") }
            jsonPath("$.type") { value("searchset") }
            jsonPath("$.total") { value(1) }
            jsonPath("$.entry[0].resource.id") { value(statement.id.value.toString()) }
            jsonPath("$.entry[0].fullUrl") { value(org.hamcrest.Matchers.endsWith("/fhir/r4/MedicationStatement/${statement.id.value}")) }
            jsonPath("$.entry[0].search.mode") { value("match") }
        }

        val audit = auditRow(correlationId)
        assertEquals("SEARCH", audit.operation)
        assertEquals("SUCCESS", audit.outcome)

        mockMvc.get("/fhir/r4/MedicationStatement") {
            param("patient", "Patient/${patient.id.value}")
            header("Authorization", "Bearer ${member.token}")
        }.andExpect {
            status { isOk() }
            jsonPath("$.total") { value(1) }
        }
    }

    @Test
    fun `fhir medication statement search errors`() {
        val member = createMember(MembershipRole.CLINICIAN, "user/MedicationStatement.read")
        val otherOrganization = createOrganization()
        val otherPatient = createPatient(otherOrganization)

        mockMvc.get("/fhir/r4/MedicationStatement") {
            header("Authorization", "Bearer ${member.token}")
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.resourceType") { value("OperationOutcome") }
            jsonPath("$.issue[0].code") { value("invalid") }
        }

        mockMvc.get("/fhir/r4/MedicationStatement") {
            param("patient", otherPatient.id.value.toString())
            header("Authorization", "Bearer ${member.token}")
        }.andExpect {
            status { isNotFound() }
            jsonPath("$.resourceType") { value("OperationOutcome") }
        }
    }

    private fun createOrganization(): Organization {
        val suffix = UUID.randomUUID()
        return organizationRepository.create(
            slug = "fhir-med-org-$suffix",
            displayName = "Fhir Med Org $suffix",
        )
    }

    private fun createMember(
        role: MembershipRole,
        scopes: String,
    ): FhirMemberFixture {
        val suffix = UUID.randomUUID()
        val organization = createOrganization()
        val user = userRepository.create(
            externalSubject = "fhir-med-user-$suffix",
            email = "fhir-med-user-$suffix@example.test",
            displayName = "Fhir Med User $suffix",
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
                effectiveStart = LocalDate.of(2026, 1, 1),
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
