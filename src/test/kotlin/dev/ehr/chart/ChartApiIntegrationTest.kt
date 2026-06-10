package dev.ehr.chart

import dev.ehr.allergy.AllergyCreateCommand
import dev.ehr.allergy.AllergyRepository
import dev.ehr.condition.ConditionCreateCommand
import dev.ehr.condition.ConditionRepository
import dev.ehr.encounter.Encounter
import dev.ehr.encounter.EncounterCreateCommand
import dev.ehr.encounter.EncounterRepository
import dev.ehr.identity.MembershipRepository
import dev.ehr.identity.MembershipRole
import dev.ehr.identity.Organization
import dev.ehr.identity.OrganizationRepository
import dev.ehr.identity.User
import dev.ehr.identity.UserRepository
import dev.ehr.medication.MedicationStatementCreateCommand
import dev.ehr.medication.MedicationStatementRepository
import dev.ehr.note.ClinicalNoteCreateCommand
import dev.ehr.note.ClinicalNoteRepository
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
import dev.ehr.testsupport.EncounterTestFixtures
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
class ChartApiIntegrationTest : PostgresIntegrationTest() {
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
    lateinit var allergyRepository: AllergyRepository

    @Autowired
    lateinit var medicationStatementRepository: MedicationStatementRepository

    @Autowired
    lateinit var observationRepository: ObservationRepository

    @Autowired
    lateinit var clinicalNoteRepository: ClinicalNoteRepository

    @Autowired
    lateinit var codingRepository: CodingRepository

    @Autowired
    lateinit var codeableConceptRepository: CodeableConceptRepository

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    lateinit var jwtEncoder: JwtEncoder

    lateinit var concept: CodeableConcept
    lateinit var classConcept: CodeableConcept

    @BeforeEach
    fun setUpConcepts() {
        concept = TerminologyTestFixtures(codingRepository, codeableConceptRepository)
            .findOrCreateConcept(
                system = CanonicalCodeSystems.SNOMED_CT,
                code = "38341003",
                display = "Hypertensive disorder",
            )
        classConcept = EncounterTestFixtures(codingRepository, codeableConceptRepository)
            .createEncounterClassConcept()
    }

    @Test
    fun `chart returns the full longitudinal record for a patient and audits one chart read`() {
        val correlationId = "chart-read-${UUID.randomUUID()}"
        val member = createMember(MembershipRole.CLINICIAN, "user/*.read")
        val patient = createPatient(member.organization)
        val encounter = encounterRepository.create(
            EncounterCreateCommand(
                organizationId = member.organization.id,
                patientId = patient.id,
                classConceptId = classConcept.id,
                periodStart = Instant.parse("2026-06-01T09:00:00Z"),
            ),
        )
        conditionRepository.create(
            ConditionCreateCommand(
                organizationId = member.organization.id,
                patientId = patient.id,
                codeConceptId = concept.id,
                encounterId = encounter.id,
            ),
        )
        allergyRepository.create(
            AllergyCreateCommand(
                organizationId = member.organization.id,
                patientId = patient.id,
                codeConceptId = concept.id,
            ),
        )
        medicationStatementRepository.create(
            MedicationStatementCreateCommand(
                organizationId = member.organization.id,
                patientId = patient.id,
                medicationConceptId = concept.id,
            ),
        )
        observationRepository.create(
            ObservationCreateCommand(
                organizationId = member.organization.id,
                patientId = patient.id,
                category = ObservationCategory.VITAL_SIGNS,
                codeConceptId = concept.id,
                value = ObservationValue.Quantity(BigDecimal("72"), "/min"),
                effectiveAt = Instant.parse("2026-06-01T09:30:00Z"),
            ),
        )
        clinicalNoteRepository.create(
            ClinicalNoteCreateCommand(
                organizationId = member.organization.id,
                patientId = patient.id,
                encounterId = encounter.id,
                typeConceptId = concept.id,
                title = "Progress note",
                contentText = "Synthetic note body.",
            ),
        )

        mockMvc.get("/api/v1/patients/${patient.id.value}/chart") {
            header("Authorization", "Bearer ${member.token}")
            header("X-Correlation-Id", correlationId)
        }.andExpect {
            status { isOk() }
            jsonPath("$.patient.id") { value(patient.id.value.toString()) }
            jsonPath("$.encounters.length()") { value(1) }
            jsonPath("$.conditions.length()") { value(1) }
            jsonPath("$.allergies.length()") { value(1) }
            jsonPath("$.medicationStatements.length()") { value(1) }
            jsonPath("$.observations.length()") { value(1) }
            jsonPath("$.notes.length()") { value(1) }
            jsonPath("$.notes[0].title") { value("Progress note") }
        }

        val audit = auditRow(correlationId)
        assertEquals("CHART", audit.resourceType)
        assertEquals("READ", audit.operation)
        assertEquals("SUCCESS", audit.outcome)
        assertEquals(patient.id.value.toString(), audit.patientId)
        assertEquals("policy-spine-v9", audit.policyVersion)
    }

    @Test
    fun `chart for another organizations patient returns 404 and audits a failed read`() {
        val correlationId = "chart-cross-${UUID.randomUUID()}"
        val member = createMember(MembershipRole.CLINICIAN, "user/*.read")
        val otherOrganization = createOrganization()
        val otherPatient = createPatient(otherOrganization)

        mockMvc.get("/api/v1/patients/${otherPatient.id.value}/chart") {
            header("Authorization", "Bearer ${member.token}")
            header("X-Correlation-Id", correlationId)
        }.andExpect {
            status { isNotFound() }
        }

        val audit = auditRow(correlationId)
        assertEquals("CHART", audit.resourceType)
        assertEquals("READ", audit.operation)
        assertEquals("FAILURE", audit.outcome)
    }

    @Test
    fun `staff and admin cannot read charts and narrow scopes are rejected`() {
        listOf(
            Triple(MembershipRole.STAFF, "user/*.read", "INSUFFICIENT_ROLE"),
            Triple(MembershipRole.ORG_ADMIN, "user/*.read", "INSUFFICIENT_ROLE"),
            Triple(MembershipRole.CLINICIAN, "user/Patient.read", "INSUFFICIENT_SCOPE"),
        ).forEach { (role, scopes, expectedReason) ->
            val correlationId = "chart-deny-$role-$expectedReason-${UUID.randomUUID()}"
            val member = createMember(role, scopes)
            val patient = createPatient(member.organization)

            mockMvc.get("/api/v1/patients/${patient.id.value}/chart") {
                header("Authorization", "Bearer ${member.token}")
                header("X-Correlation-Id", correlationId)
            }.andExpect {
                status { isForbidden() }
            }

            val audit = auditRow(correlationId)
            assertEquals("AUTHORIZATION_DENIED", audit.operation)
            assertEquals(expectedReason, audit.policyReasonCode)
        }
    }

    @Test
    fun `chart rejects unauthenticated requests without audit`() {
        val correlationId = "chart-unauth-${UUID.randomUUID()}"

        mockMvc.get("/api/v1/patients/${UUID.randomUUID()}/chart") {
            header("X-Correlation-Id", correlationId)
        }.andExpect {
            status { isUnauthorized() }
        }

        assertEquals(0, auditCount(correlationId))
    }

    private fun createOrganization(): Organization {
        val suffix = UUID.randomUUID()
        return organizationRepository.create(
            slug = "chart-org-$suffix",
            displayName = "Chart Org $suffix",
        )
    }

    private fun createMember(
        role: MembershipRole,
        scopes: String,
    ): ChartMemberFixture {
        val suffix = UUID.randomUUID()
        val organization = createOrganization()
        val user = userRepository.create(
            externalSubject = "chart-user-$suffix",
            email = "chart-user-$suffix@example.test",
            displayName = "Chart User $suffix",
        )
        val membership = membershipRepository.create(
            organizationId = organization.id,
            userId = user.id,
        )
        membershipRepository.addRole(membership.id, role)

        return ChartMemberFixture(
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

    private fun auditCount(correlationId: String): Int =
        jdbcTemplate.queryForObject(
            "select count(*) from audit_events where correlation_id = ?",
            Int::class.java,
            correlationId,
        )!!

    private fun auditRow(correlationId: String): ChartAuditRow {
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
                ChartAuditRow(
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

data class ChartMemberFixture(
    val organization: Organization,
    val user: User,
    val token: String,
)

data class ChartAuditRow(
    val patientId: String?,
    val resourceType: String,
    val operation: String,
    val outcome: String,
    val policyVersion: String?,
    val policyReasonCode: String?,
)
