package dev.ehr.fhir

import dev.ehr.identity.MembershipRepository
import dev.ehr.identity.MembershipRole
import dev.ehr.identity.Organization
import dev.ehr.identity.OrganizationRepository
import dev.ehr.identity.UserRepository
import dev.ehr.observation.Observation
import dev.ehr.observation.ObservationCategory
import dev.ehr.observation.ObservationCreateCommand
import dev.ehr.observation.ObservationRepository
import dev.ehr.observation.ObservationValue
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
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

@AutoConfigureMockMvc
@Import(DevJwtTestConfiguration::class)
class ObservationFhirApiIntegrationTest : PostgresIntegrationTest() {
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

    private val effective = Instant.parse("2026-06-01T09:30:00Z")

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
    fun `fhir observation endpoints reject unauthenticated requests without audit`() {
        val correlationId = "fhir-obs-unauth-${UUID.randomUUID()}"

        mockMvc.get("/fhir/r4/Observation/${UUID.randomUUID()}") {
            header("X-Correlation-Id", correlationId)
        }.andExpect {
            status { isUnauthorized() }
        }

        assertEquals(0, auditCount(correlationId))
    }

    @Test
    fun `clinician can read a quantity fhir observation and the read is audited`() {
        val correlationId = "fhir-obs-read-${UUID.randomUUID()}"
        val member = createMember(MembershipRole.CLINICIAN, "user/Observation.read")
        val patient = createPatient(member.organization)
        val observation = createObservation(member.organization, patient)

        mockMvc.get("/fhir/r4/Observation/${observation.id.value}") {
            header("Authorization", "Bearer ${member.token}")
            header("X-Correlation-Id", correlationId)
        }.andExpect {
            status { isOk() }
            content { contentTypeCompatibleWith("application/fhir+json") }
            jsonPath("$.resourceType") { value("Observation") }
            jsonPath("$.id") { value(observation.id.value.toString()) }
            jsonPath("$.status") { value("final") }
            jsonPath("$.category[0].coding[0].system") { value("http://terminology.hl7.org/CodeSystem/observation-category") }
            jsonPath("$.category[0].coding[0].code") { value("vital-signs") }
            jsonPath("$.code.coding[0].system") { value("http://loinc.org") }
            jsonPath("$.code.coding[0].code") { value("8867-4") }
            jsonPath("$.subject.reference") { value("Patient/${patient.id.value}") }
            jsonPath("$.effectiveDateTime") { value("2026-06-01T09:30:00Z") }
            jsonPath("$.valueQuantity.value") { value(72) }
            jsonPath("$.valueQuantity.unit") { value("/min") }
            jsonPath("$.valueQuantity.system") { value("http://unitsofmeasure.org") }
            jsonPath("$.valueQuantity.code") { value("/min") }
        }

        val audit = auditRow(correlationId)
        assertEquals("OBSERVATION", audit.resourceType)
        assertEquals("READ", audit.operation)
        assertEquals("SUCCESS", audit.outcome)
        assertEquals(patient.id.value.toString(), audit.patientId)
    }

    @Test
    fun `coded and text fhir observation values render as fhir value types`() {
        val member = createMember(MembershipRole.CLINICIAN, "user/Observation.read")
        val patient = createPatient(member.organization)
        val coded = createObservation(
            member.organization,
            patient,
            value = ObservationValue.Coded(codedResultConcept.id),
            category = ObservationCategory.LABORATORY,
        )
        val text = createObservation(
            member.organization,
            patient,
            value = ObservationValue.Text("Trace protein"),
            category = ObservationCategory.LABORATORY,
        )

        mockMvc.get("/fhir/r4/Observation/${coded.id.value}") {
            header("Authorization", "Bearer ${member.token}")
        }.andExpect {
            status { isOk() }
            jsonPath("$.valueCodeableConcept.coding[0].code") { value("260385009") }
            jsonPath("$.valueCodeableConcept.text") { value("Negative") }
        }

        mockMvc.get("/fhir/r4/Observation/${text.id.value}") {
            header("Authorization", "Bearer ${member.token}")
        }.andExpect {
            status { isOk() }
            jsonPath("$.valueString") { value("Trace protein") }
        }
    }

    @Test
    fun `cross organization fhir observation read returns operation outcome not found`() {
        val member = createMember(MembershipRole.CLINICIAN, "user/Observation.read")
        val otherOrganization = createOrganization()
        val otherPatient = createPatient(otherOrganization)
        val otherObservation = createObservation(otherOrganization, otherPatient)

        mockMvc.get("/fhir/r4/Observation/${otherObservation.id.value}") {
            header("Authorization", "Bearer ${member.token}")
        }.andExpect {
            status { isNotFound() }
            jsonPath("$.resourceType") { value("OperationOutcome") }
            jsonPath("$.issue[0].code") { value("not-found") }
        }
    }

    @Test
    fun `staff fhir observation read returns operation outcome forbidden`() {
        val member = createMember(MembershipRole.STAFF, "user/*.read")
        val patient = createPatient(member.organization)
        val observation = createObservation(member.organization, patient)

        mockMvc.get("/fhir/r4/Observation/${observation.id.value}") {
            header("Authorization", "Bearer ${member.token}")
        }.andExpect {
            status { isForbidden() }
            jsonPath("$.resourceType") { value("OperationOutcome") }
            jsonPath("$.issue[0].code") { value("forbidden") }
        }
    }

    @Test
    fun `fhir observation compartment search filters by category`() {
        val correlationId = "fhir-obs-search-${UUID.randomUUID()}"
        val member = createMember(MembershipRole.CLINICIAN, "user/Observation.read")
        val patient = createPatient(member.organization)
        val vitalSign = createObservation(member.organization, patient, category = ObservationCategory.VITAL_SIGNS)
        createObservation(member.organization, patient, category = ObservationCategory.LABORATORY)

        mockMvc.get("/fhir/r4/Observation") {
            param("patient", patient.id.value.toString())
            param("category", "vital-signs")
            header("Authorization", "Bearer ${member.token}")
            header("X-Correlation-Id", correlationId)
        }.andExpect {
            status { isOk() }
            jsonPath("$.resourceType") { value("Bundle") }
            jsonPath("$.type") { value("searchset") }
            jsonPath("$.total") { value(1) }
            jsonPath("$.entry[0].resource.id") { value(vitalSign.id.value.toString()) }
            jsonPath("$.entry[0].search.mode") { value("match") }
        }

        mockMvc.get("/fhir/r4/Observation") {
            param("patient", "Patient/${patient.id.value}")
            header("Authorization", "Bearer ${member.token}")
        }.andExpect {
            status { isOk() }
            jsonPath("$.total") { value(2) }
        }

        val audit = auditRow(correlationId)
        assertEquals("SEARCH", audit.operation)
        assertEquals("SUCCESS", audit.outcome)
    }

    @Test
    fun `fhir observation search filters by code and date`() {
        val member = createMember(MembershipRole.CLINICIAN, "user/Observation.read")
        val patient = createPatient(member.organization)
        createObservation(member.organization, patient)

        // Code: system|code match, bare-code match, and a non-match.
        mockMvc.get("/fhir/r4/Observation") {
            param("patient", patient.id.value.toString())
            param("code", "http://loinc.org|8867-4")
            header("Authorization", "Bearer ${member.token}")
        }.andExpect {
            status { isOk() }
            jsonPath("$.total") { value(1) }
        }
        mockMvc.get("/fhir/r4/Observation") {
            param("patient", patient.id.value.toString())
            param("code", "8867-4")
            header("Authorization", "Bearer ${member.token}")
        }.andExpect {
            status { isOk() }
            jsonPath("$.total") { value(1) }
        }
        mockMvc.get("/fhir/r4/Observation") {
            param("patient", patient.id.value.toString())
            param("code", "http://loinc.org|0000-0")
            header("Authorization", "Bearer ${member.token}")
        }.andExpect {
            status { isOk() }
            jsonPath("$.total") { value(0) }
        }

        // Date ranges AND together; malformed input is refused.
        mockMvc.get("/fhir/r4/Observation") {
            param("patient", patient.id.value.toString())
            param("date", "ge2000-01-01")
            header("Authorization", "Bearer ${member.token}")
        }.andExpect {
            status { isOk() }
            jsonPath("$.total") { value(1) }
        }
        mockMvc.get("/fhir/r4/Observation") {
            param("patient", patient.id.value.toString())
            param("date", "lt2000-01-01")
            header("Authorization", "Bearer ${member.token}")
        }.andExpect {
            status { isOk() }
            jsonPath("$.total") { value(0) }
        }
        mockMvc.get("/fhir/r4/Observation") {
            param("patient", patient.id.value.toString())
            param("date", "not-a-date")
            header("Authorization", "Bearer ${member.token}")
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.resourceType") { value("OperationOutcome") }
        }
    }

    @Test
    fun `fhir observation search rejects unknown category and missing patient`() {
        val member = createMember(MembershipRole.CLINICIAN, "user/Observation.read")
        val patient = createPatient(member.organization)

        mockMvc.get("/fhir/r4/Observation") {
            param("patient", patient.id.value.toString())
            param("category", "imaging")
            header("Authorization", "Bearer ${member.token}")
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.resourceType") { value("OperationOutcome") }
            jsonPath("$.issue[0].code") { value("invalid") }
        }

        mockMvc.get("/fhir/r4/Observation") {
            header("Authorization", "Bearer ${member.token}")
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.resourceType") { value("OperationOutcome") }
        }
    }

    @Test
    fun `fhir observation search for another organizations patient returns not found`() {
        val member = createMember(MembershipRole.CLINICIAN, "user/Observation.read")
        val otherOrganization = createOrganization()
        val otherPatient = createPatient(otherOrganization)
        createObservation(otherOrganization, otherPatient)

        mockMvc.get("/fhir/r4/Observation") {
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
            slug = "fhir-obs-org-$suffix",
            displayName = "Fhir Obs Org $suffix",
        )
    }

    private fun createMember(
        role: MembershipRole,
        scopes: String,
    ): FhirMemberFixture {
        val suffix = UUID.randomUUID()
        val organization = createOrganization()
        val user = userRepository.create(
            externalSubject = "fhir-obs-user-$suffix",
            email = "fhir-obs-user-$suffix@example.test",
            displayName = "Fhir Obs User $suffix",
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

    private fun createObservation(
        organization: Organization,
        patient: Patient,
        value: ObservationValue = ObservationValue.Quantity(BigDecimal("72"), "/min"),
        category: ObservationCategory = ObservationCategory.VITAL_SIGNS,
    ): Observation =
        observationRepository.create(
            ObservationCreateCommand(
                organizationId = organization.id,
                patientId = patient.id,
                category = category,
                codeConceptId = heartRateConcept.id,
                value = value,
                effectiveAt = effective,
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
