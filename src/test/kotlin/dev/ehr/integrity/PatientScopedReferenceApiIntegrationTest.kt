package dev.ehr.integrity

import dev.ehr.encounter.Encounter
import dev.ehr.encounter.EncounterCreateCommand
import dev.ehr.encounter.EncounterRepository
import dev.ehr.identity.MembershipRepository
import dev.ehr.identity.MembershipRole
import dev.ehr.identity.Organization
import dev.ehr.identity.OrganizationRepository
import dev.ehr.identity.UserRepository
import dev.ehr.observation.ObservationCreateCommand
import dev.ehr.observation.ObservationCategory
import dev.ehr.observation.ObservationRepository
import dev.ehr.observation.ObservationValue
import dev.ehr.order.OrderCreateCommand
import dev.ehr.order.OrderRepository
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
import org.springframework.test.web.servlet.post
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

@AutoConfigureMockMvc
@Import(DevJwtTestConfiguration::class)
class PatientScopedReferenceApiIntegrationTest : PostgresIntegrationTest() {
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
    lateinit var orderRepository: OrderRepository

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

    lateinit var clinicalConcept: CodeableConcept
    lateinit var medicationConcept: CodeableConcept
    lateinit var labConcept: CodeableConcept
    lateinit var encounterClassConcept: CodeableConcept

    @BeforeEach
    fun setUpConcepts() {
        val terminology = TerminologyTestFixtures(codingRepository, codeableConceptRepository)
        clinicalConcept = terminology.findOrCreateConcept(
            system = CanonicalCodeSystems.SNOMED_CT,
            code = "38341003",
            display = "Hypertensive disorder",
        )
        medicationConcept = terminology.findOrCreateConcept(
            system = CanonicalCodeSystems.RXNORM,
            code = "197361",
            display = "Lisinopril 10 MG Oral Tablet",
        )
        labConcept = terminology.findOrCreateConcept(
            system = CanonicalCodeSystems.LOINC,
            code = "24323-8",
            display = "Comprehensive metabolic panel",
        )
        encounterClassConcept = EncounterTestFixtures(codingRepository, codeableConceptRepository)
            .createEncounterClassConcept()
    }

    @Test
    fun `patient scoped write endpoints reject same organization encounters from a different patient`() {
        val fixture = createFixture()
        val wrongPatientEncounter = createEncounter(fixture.organization, fixture.otherPatient)

        postJson(
            fixture,
            "/api/v1/patients/${fixture.patient.id.value}/conditions",
            """{"codeConceptId":"${clinicalConcept.id.value}","encounterId":"${wrongPatientEncounter.id.value}"}""",
        )
        postJson(
            fixture,
            "/api/v1/patients/${fixture.patient.id.value}/allergies",
            """{"codeConceptId":"${clinicalConcept.id.value}","encounterId":"${wrongPatientEncounter.id.value}"}""",
        )
        postJson(
            fixture,
            "/api/v1/patients/${fixture.patient.id.value}/observations",
            """
            {
              "codeConceptId": "${labConcept.id.value}",
              "category": "LABORATORY",
              "effectiveAt": "2026-06-01T09:30:00Z",
              "valueQuantity": {"value": 4.5, "unit": "mmol/L"},
              "encounterId": "${wrongPatientEncounter.id.value}"
            }
            """.trimIndent(),
        )
        postJson(
            fixture,
            "/api/v1/patients/${fixture.patient.id.value}/medication-statements",
            """
            {
              "medicationConceptId": "${medicationConcept.id.value}",
              "encounterId": "${wrongPatientEncounter.id.value}"
            }
            """.trimIndent(),
        )
        postJson(
            fixture,
            "/api/v1/orders",
            """
            {
              "patientId": "${fixture.patient.id.value}",
              "codeConceptId": "${labConcept.id.value}",
              "encounterId": "${wrongPatientEncounter.id.value}"
            }
            """.trimIndent(),
        )

        assertEquals(0, tableCount("conditions", fixture.organization))
        assertEquals(0, tableCount("allergies", fixture.organization))
        assertEquals(0, tableCount("observations", fixture.organization))
        assertEquals(0, tableCount("medication_statements", fixture.organization))
        assertEquals(0, tableCount("orders", fixture.organization))
    }

    @Test
    fun `attaching a diagnostic result rejects a same organization encounter from a different patient`() {
        val fixture = createFixture()
        val wrongPatientEncounter = createEncounter(fixture.organization, fixture.otherPatient)
        val order = orderRepository.create(
            OrderCreateCommand(
                organizationId = fixture.organization.id,
                patientId = fixture.patient.id,
                codeConceptId = labConcept.id,
            ),
        )
        val observation = observationRepository.create(
            ObservationCreateCommand(
                organizationId = fixture.organization.id,
                patientId = fixture.patient.id,
                category = ObservationCategory.LABORATORY,
                codeConceptId = labConcept.id,
                value = ObservationValue.Quantity(BigDecimal("4.5"), "mmol/L"),
                effectiveAt = Instant.parse("2026-06-01T09:30:00Z"),
            ),
        )

        mockMvc.post("/api/v1/orders/${order.id.value}/results") {
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "codeConceptId": "${labConcept.id.value}",
                  "resultObservationIds": ["${observation.id.value}"],
                  "encounterId": "${wrongPatientEncounter.id.value}"
                }
            """.trimIndent()
            header("Authorization", "Bearer ${fixture.token}")
        }.andExpect {
            status { isBadRequest() }
        }

        assertEquals(0, tableCount("diagnostic_reports", fixture.organization))
    }

    private fun postJson(
        fixture: ApiFixture,
        path: String,
        body: String,
    ) {
        mockMvc.post(path) {
            contentType = MediaType.APPLICATION_JSON
            content = body
            header("Authorization", "Bearer ${fixture.token}")
        }.andExpect {
            status { isBadRequest() }
        }
    }

    private fun createFixture(): ApiFixture {
        val suffix = UUID.randomUUID()
        val organization = organizationRepository.create(
            slug = "integrity-api-org-$suffix",
            displayName = "Integrity Api Org $suffix",
        )
        val user = userRepository.create(
            externalSubject = "integrity-api-user-$suffix",
            email = "integrity-api-user-$suffix@example.test",
            displayName = "Integrity Api User $suffix",
        )
        val membership = membershipRepository.create(
            organizationId = organization.id,
            userId = user.id,
        )
        membershipRepository.addRole(membership.id, MembershipRole.CLINICIAN)
        return ApiFixture(
            organization = organization,
            patient = createPatient(organization),
            otherPatient = createPatient(organization),
            token = DevJwtFactory(jwtEncoder).tokenFor(
                user = user,
                organization = organization,
                scopes = "user/*.read user/*.write",
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
    ): Encounter =
        encounterRepository.create(
            EncounterCreateCommand(
                organizationId = organization.id,
                patientId = patient.id,
                classConceptId = encounterClassConcept.id,
                periodStart = Instant.parse("2026-06-01T09:00:00Z"),
            ),
        )

    private fun tableCount(
        tableName: String,
        organization: Organization,
    ): Int =
        jdbcTemplate.queryForObject(
            "select count(*) from $tableName where organization_id = ?",
            Int::class.java,
            organization.id.value,
        )!!
}

data class ApiFixture(
    val organization: Organization,
    val patient: Patient,
    val otherPatient: Patient,
    val token: String,
)
