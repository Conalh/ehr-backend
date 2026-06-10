package dev.ehr.fhir

import dev.ehr.encounter.Encounter
import dev.ehr.encounter.EncounterCreateCommand
import dev.ehr.encounter.EncounterRepository
import dev.ehr.encounter.EncounterStatus
import dev.ehr.identity.MembershipRepository
import dev.ehr.identity.MembershipRole
import dev.ehr.identity.Organization
import dev.ehr.identity.OrganizationRepository
import dev.ehr.identity.UserRepository
import dev.ehr.patient.Patient
import dev.ehr.patient.PatientCreateCommand
import dev.ehr.patient.PatientRepository
import dev.ehr.terminology.CodeableConcept
import dev.ehr.terminology.CodeableConceptRepository
import dev.ehr.terminology.CodingRepository
import dev.ehr.testsupport.DevJwtFactory
import dev.ehr.testsupport.DevJwtTestConfiguration
import dev.ehr.testsupport.EncounterTestFixtures
import dev.ehr.testsupport.PostgresIntegrationTest
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
import java.time.Instant
import java.util.UUID

@AutoConfigureMockMvc
@Import(DevJwtTestConfiguration::class)
class EncounterFhirApiIntegrationTest : PostgresIntegrationTest() {
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
    lateinit var codingRepository: CodingRepository

    @Autowired
    lateinit var codeableConceptRepository: CodeableConceptRepository

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    lateinit var jwtEncoder: JwtEncoder

    lateinit var classConcept: CodeableConcept

    private val start = Instant.parse("2026-06-01T09:00:00Z")

    @BeforeEach
    fun setUpConcept() {
        classConcept = EncounterTestFixtures(codingRepository, codeableConceptRepository)
            .createEncounterClassConcept()
    }

    @Test
    fun `fhir encounter endpoints reject unauthenticated requests without audit`() {
        val correlationId = "fhir-enc-unauth-${UUID.randomUUID()}"

        mockMvc.get("/fhir/r4/Encounter/${UUID.randomUUID()}") {
            header("X-Correlation-Id", correlationId)
        }.andExpect {
            status { isUnauthorized() }
        }

        assertEquals(0, auditCount(correlationId))
    }

    @Test
    fun `clinician can read a fhir encounter and the read is audited`() {
        val correlationId = "fhir-enc-read-${UUID.randomUUID()}"
        val member = createMember(MembershipRole.CLINICIAN, "user/Encounter.read")
        val patient = createPatient(member.organization)
        val encounter = createEncounter(member.organization, patient, status = EncounterStatus.IN_PROGRESS)

        mockMvc.get("/fhir/r4/Encounter/${encounter.id.value}") {
            header("Authorization", "Bearer ${member.token}")
            header("X-Correlation-Id", correlationId)
        }.andExpect {
            status { isOk() }
            content { contentTypeCompatibleWith("application/fhir+json") }
            jsonPath("$.resourceType") { value("Encounter") }
            jsonPath("$.id") { value(encounter.id.value.toString()) }
            jsonPath("$.meta.versionId") { value("1") }
            jsonPath("$.status") { value("in-progress") }
            jsonPath("$.class.system") { value("http://terminology.hl7.org/CodeSystem/v3-ActCode") }
            jsonPath("$.class.code") { value("AMB") }
            jsonPath("$.subject.reference") { value("Patient/${patient.id.value}") }
            jsonPath("$.period.start") { value("2026-06-01T09:00:00Z") }
        }

        val audit = auditRow(correlationId)
        assertEquals("ENCOUNTER", audit.resourceType)
        assertEquals("READ", audit.operation)
        assertEquals("SUCCESS", audit.outcome)
        assertEquals(patient.id.value.toString(), audit.patientId)
        assertEquals(encounter.id.value.toString(), audit.resourceId)
    }

    @Test
    fun `cross organization fhir encounter read returns operation outcome not found`() {
        val correlationId = "fhir-enc-cross-${UUID.randomUUID()}"
        val member = createMember(MembershipRole.CLINICIAN, "user/Encounter.read")
        val otherOrganization = createOrganization()
        val otherPatient = createPatient(otherOrganization)
        val otherEncounter = createEncounter(otherOrganization, otherPatient)

        mockMvc.get("/fhir/r4/Encounter/${otherEncounter.id.value}") {
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
    fun `non uuid fhir encounter id returns operation outcome not found without audit`() {
        val correlationId = "fhir-enc-badid-${UUID.randomUUID()}"
        val member = createMember(MembershipRole.CLINICIAN, "user/Encounter.read")

        mockMvc.get("/fhir/r4/Encounter/not-a-uuid") {
            header("Authorization", "Bearer ${member.token}")
            header("X-Correlation-Id", correlationId)
        }.andExpect {
            status { isNotFound() }
            jsonPath("$.resourceType") { value("OperationOutcome") }
        }

        assertEquals(0, auditCount(correlationId))
    }

    @Test
    fun `org admin fhir encounter read returns operation outcome forbidden and audits denial`() {
        val correlationId = "fhir-enc-admin-${UUID.randomUUID()}"
        val member = createMember(MembershipRole.ORG_ADMIN, "user/*.read")
        val patient = createPatient(member.organization)
        val encounter = createEncounter(member.organization, patient)

        mockMvc.get("/fhir/r4/Encounter/${encounter.id.value}") {
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
    fun `fhir encounter compartment search returns newest first bundle in both patient forms`() {
        val bareCorrelationId = "fhir-enc-search-bare-${UUID.randomUUID()}"
        val referenceCorrelationId = "fhir-enc-search-ref-${UUID.randomUUID()}"
        val member = createMember(MembershipRole.STAFF, "user/Encounter.read")
        val patient = createPatient(member.organization)
        val earlier = createEncounter(member.organization, patient, periodStart = start)
        val later = createEncounter(member.organization, patient, periodStart = start.plusSeconds(86_400))

        mockMvc.get("/fhir/r4/Encounter") {
            param("patient", patient.id.value.toString())
            header("Authorization", "Bearer ${member.token}")
            header("X-Correlation-Id", bareCorrelationId)
        }.andExpect {
            status { isOk() }
            jsonPath("$.resourceType") { value("Bundle") }
            jsonPath("$.type") { value("searchset") }
            jsonPath("$.total") { value(2) }
            jsonPath("$.entry[0].resource.id") { value(later.id.value.toString()) }
            jsonPath("$.entry[1].resource.id") { value(earlier.id.value.toString()) }
            jsonPath("$.entry[0].fullUrl") { value(org.hamcrest.Matchers.endsWith("/fhir/r4/Encounter/${later.id.value}")) }
            jsonPath("$.entry[0].search.mode") { value("match") }
        }

        val bareAudit = auditRow(bareCorrelationId)
        assertEquals("SEARCH", bareAudit.operation)
        assertEquals("SUCCESS", bareAudit.outcome)
        assertEquals(patient.id.value.toString(), bareAudit.patientId)

        mockMvc.get("/fhir/r4/Encounter") {
            param("patient", "Patient/${patient.id.value}")
            header("Authorization", "Bearer ${member.token}")
            header("X-Correlation-Id", referenceCorrelationId)
        }.andExpect {
            status { isOk() }
            jsonPath("$.total") { value(2) }
        }

        assertEquals(1, auditCount(referenceCorrelationId))
    }

    @Test
    fun `fhir encounter search for another organizations patient returns not found`() {
        val correlationId = "fhir-enc-search-cross-${UUID.randomUUID()}"
        val member = createMember(MembershipRole.STAFF, "user/Encounter.read")
        val otherOrganization = createOrganization()
        val otherPatient = createPatient(otherOrganization)
        createEncounter(otherOrganization, otherPatient)

        mockMvc.get("/fhir/r4/Encounter") {
            param("patient", otherPatient.id.value.toString())
            header("Authorization", "Bearer ${member.token}")
            header("X-Correlation-Id", correlationId)
        }.andExpect {
            status { isNotFound() }
            jsonPath("$.resourceType") { value("OperationOutcome") }
            jsonPath("$.issue[0].code") { value("not-found") }
        }

        val audit = auditRow(correlationId)
        assertEquals("SEARCH", audit.operation)
        assertEquals("FAILURE", audit.outcome)
    }

    @Test
    fun `fhir encounter search without patient parameter returns operation outcome invalid`() {
        val member = createMember(MembershipRole.STAFF, "user/Encounter.read")

        listOf(null, "not-a-uuid", "Patient/not-a-uuid", " ").forEach { patientParam ->
            mockMvc.get("/fhir/r4/Encounter") {
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
            slug = "fhir-enc-org-$suffix",
            displayName = "Fhir Enc Org $suffix",
        )
    }

    private fun createMember(
        role: MembershipRole,
        scopes: String,
    ): FhirMemberFixture {
        val suffix = UUID.randomUUID()
        val organization = createOrganization()
        val user = userRepository.create(
            externalSubject = "fhir-enc-user-$suffix",
            email = "fhir-enc-user-$suffix@example.test",
            displayName = "Fhir Enc User $suffix",
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

    private fun createEncounter(
        organization: Organization,
        patient: Patient,
        status: EncounterStatus = EncounterStatus.PLANNED,
        periodStart: Instant = start,
    ): Encounter =
        encounterRepository.create(
            EncounterCreateCommand(
                organizationId = organization.id,
                patientId = patient.id,
                classConceptId = classConcept.id,
                periodStart = periodStart,
                status = status,
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

data class FhirEncounterAuditRow(
    val patientId: String?,
    val resourceType: String,
    val resourceId: String?,
    val operation: String,
    val outcome: String,
    val policyReasonCode: String?,
)
