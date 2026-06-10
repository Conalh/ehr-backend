package dev.ehr.fhir

import dev.ehr.allergy.Allergy
import dev.ehr.allergy.AllergyCategory
import dev.ehr.allergy.AllergyCreateCommand
import dev.ehr.allergy.AllergyCriticality
import dev.ehr.allergy.AllergyRepository
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
import java.util.UUID

@AutoConfigureMockMvc
@Import(DevJwtTestConfiguration::class)
class AllergyFhirApiIntegrationTest : PostgresIntegrationTest() {
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
    fun `fhir allergy endpoints reject unauthenticated requests without audit`() {
        val correlationId = "fhir-allergy-unauth-${UUID.randomUUID()}"

        mockMvc.get("/fhir/r4/AllergyIntolerance/${UUID.randomUUID()}") {
            header("X-Correlation-Id", correlationId)
        }.andExpect {
            status { isUnauthorized() }
        }

        assertEquals(0, auditCount(correlationId))
    }

    @Test
    fun `clinician can read a fhir allergy and the read is audited`() {
        val correlationId = "fhir-allergy-read-${UUID.randomUUID()}"
        val member = createMember(MembershipRole.CLINICIAN, "user/AllergyIntolerance.read")
        val patient = createPatient(member.organization)
        val allergy = createAllergy(member.organization, patient)

        mockMvc.get("/fhir/r4/AllergyIntolerance/${allergy.id.value}") {
            header("Authorization", "Bearer ${member.token}")
            header("X-Correlation-Id", correlationId)
        }.andExpect {
            status { isOk() }
            content { contentTypeCompatibleWith("application/fhir+json") }
            jsonPath("$.resourceType") { value("AllergyIntolerance") }
            jsonPath("$.id") { value(allergy.id.value.toString()) }
            jsonPath("$.clinicalStatus.coding[0].code") { value("active") }
            jsonPath("$.verificationStatus.coding[0].code") { value("confirmed") }
            jsonPath("$.code.coding[0].system") { value("http://snomed.info/sct") }
            jsonPath("$.code.coding[0].code") { value("91935009") }
            jsonPath("$.patient.reference") { value("Patient/${patient.id.value}") }
            jsonPath("$.category[0]") { value("food") }
            jsonPath("$.criticality") { value("high") }
        }

        val audit = auditRow(correlationId)
        assertEquals("ALLERGY", audit.resourceType)
        assertEquals("READ", audit.operation)
        assertEquals("SUCCESS", audit.outcome)
        assertEquals(patient.id.value.toString(), audit.patientId)
    }

    @Test
    fun `cross organization fhir allergy read returns operation outcome not found`() {
        val correlationId = "fhir-allergy-cross-${UUID.randomUUID()}"
        val member = createMember(MembershipRole.CLINICIAN, "user/AllergyIntolerance.read")
        val otherOrganization = createOrganization()
        val otherPatient = createPatient(otherOrganization)
        val otherAllergy = createAllergy(otherOrganization, otherPatient)

        mockMvc.get("/fhir/r4/AllergyIntolerance/${otherAllergy.id.value}") {
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
    fun `staff fhir allergy read returns operation outcome forbidden`() {
        val correlationId = "fhir-allergy-staff-${UUID.randomUUID()}"
        val member = createMember(MembershipRole.STAFF, "user/*.read")
        val patient = createPatient(member.organization)
        val allergy = createAllergy(member.organization, patient)

        mockMvc.get("/fhir/r4/AllergyIntolerance/${allergy.id.value}") {
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
    fun `fhir allergy compartment search returns bundle in both patient forms`() {
        val correlationId = "fhir-allergy-search-${UUID.randomUUID()}"
        val member = createMember(MembershipRole.CLINICIAN, "user/AllergyIntolerance.read")
        val patient = createPatient(member.organization)
        val allergy = createAllergy(member.organization, patient)

        mockMvc.get("/fhir/r4/AllergyIntolerance") {
            param("patient", patient.id.value.toString())
            header("Authorization", "Bearer ${member.token}")
            header("X-Correlation-Id", correlationId)
        }.andExpect {
            status { isOk() }
            jsonPath("$.resourceType") { value("Bundle") }
            jsonPath("$.type") { value("searchset") }
            jsonPath("$.total") { value(1) }
            jsonPath("$.entry[0].resource.id") { value(allergy.id.value.toString()) }
            jsonPath("$.entry[0].fullUrl") { value(org.hamcrest.Matchers.endsWith("/fhir/r4/AllergyIntolerance/${allergy.id.value}")) }
            jsonPath("$.entry[0].search.mode") { value("match") }
        }

        val audit = auditRow(correlationId)
        assertEquals("SEARCH", audit.operation)
        assertEquals("SUCCESS", audit.outcome)
        assertEquals(patient.id.value.toString(), audit.patientId)

        mockMvc.get("/fhir/r4/AllergyIntolerance") {
            param("patient", "Patient/${patient.id.value}")
            header("Authorization", "Bearer ${member.token}")
        }.andExpect {
            status { isOk() }
            jsonPath("$.total") { value(1) }
        }
    }

    @Test
    fun `fhir allergy search for another organizations patient returns not found`() {
        val member = createMember(MembershipRole.CLINICIAN, "user/AllergyIntolerance.read")
        val otherOrganization = createOrganization()
        val otherPatient = createPatient(otherOrganization)
        createAllergy(otherOrganization, otherPatient)

        mockMvc.get("/fhir/r4/AllergyIntolerance") {
            param("patient", otherPatient.id.value.toString())
            header("Authorization", "Bearer ${member.token}")
        }.andExpect {
            status { isNotFound() }
            jsonPath("$.resourceType") { value("OperationOutcome") }
        }
    }

    @Test
    fun `fhir allergy search without usable patient parameter returns operation outcome invalid`() {
        val member = createMember(MembershipRole.CLINICIAN, "user/AllergyIntolerance.read")

        listOf(null, "not-a-uuid").forEach { patientParam ->
            mockMvc.get("/fhir/r4/AllergyIntolerance") {
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
            slug = "fhir-allergy-org-$suffix",
            displayName = "Fhir Allergy Org $suffix",
        )
    }

    private fun createMember(
        role: MembershipRole,
        scopes: String,
    ): FhirMemberFixture {
        val suffix = UUID.randomUUID()
        val organization = createOrganization()
        val user = userRepository.create(
            externalSubject = "fhir-allergy-user-$suffix",
            email = "fhir-allergy-user-$suffix@example.test",
            displayName = "Fhir Allergy User $suffix",
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

    private fun createAllergy(
        organization: Organization,
        patient: Patient,
    ): Allergy =
        allergyRepository.create(
            AllergyCreateCommand(
                organizationId = organization.id,
                patientId = patient.id,
                codeConceptId = codeConcept.id,
                category = AllergyCategory.FOOD,
                criticality = AllergyCriticality.HIGH,
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
