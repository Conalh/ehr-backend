package dev.ehr.encounter

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
class EncounterApiIntegrationTest : PostgresIntegrationTest() {
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

    private val start = "2026-06-01T09:00:00Z"

    @BeforeEach
    fun setUpConcept() {
        classConcept = EncounterTestFixtures(codingRepository, codeableConceptRepository)
            .createEncounterClassConcept()
    }

    @Test
    fun `encounter endpoints reject unauthenticated requests without audit`() {
        val correlationId = "encounter-unauth-${UUID.randomUUID()}"

        mockMvc.get("/api/v1/encounters/${UUID.randomUUID()}") {
            header("X-Correlation-Id", correlationId)
        }.andExpect {
            status { isUnauthorized() }
        }

        mockMvc.post("/api/v1/patients/${UUID.randomUUID()}/encounters") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"classConceptId":"${classConcept.id.value}","periodStart":"$start"}"""
            header("X-Correlation-Id", correlationId)
        }.andExpect {
            status { isUnauthorized() }
        }

        assertEquals(0, auditCount(correlationId))
    }

    @Test
    fun `clinician can open an encounter and the create is audited`() {
        val correlationId = "encounter-open-${UUID.randomUUID()}"
        val member = createMember(MembershipRole.CLINICIAN, "user/Encounter.write user/Encounter.read")
        val patient = createPatient(member.organization)

        mockMvc.post("/api/v1/patients/${patient.id.value}/encounters") {
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "classConceptId": "${classConcept.id.value}",
                  "periodStart": "$start",
                  "status": "IN_PROGRESS"
                }
            """.trimIndent()
            header("Authorization", "Bearer ${member.token}")
            header("X-Correlation-Id", correlationId)
        }.andExpect {
            status { isCreated() }
            jsonPath("$.id") { exists() }
            jsonPath("$.organizationId") { value(member.organization.id.value.toString()) }
            jsonPath("$.patientId") { value(patient.id.value.toString()) }
            jsonPath("$.status") { value("in-progress") }
            jsonPath("$.classConceptId") { value(classConcept.id.value.toString()) }
            jsonPath("$.periodStart") { value(start) }
            jsonPath("$.version") { value(1) }
        }

        val audit = auditRow(correlationId)
        assertEquals("ENCOUNTER", audit.resourceType)
        assertEquals("CREATE", audit.operation)
        assertEquals("SUCCESS", audit.outcome)
        assertEquals(patient.id.value.toString(), audit.patientId)
        assertEquals("policy-spine-v6", audit.policyVersion)
    }

    @Test
    fun `staff cannot open an encounter and the denial is audited`() {
        val correlationId = "encounter-open-deny-${UUID.randomUUID()}"
        val member = createMember(MembershipRole.STAFF, "user/Encounter.write user/Encounter.read")
        val patient = createPatient(member.organization)

        mockMvc.post("/api/v1/patients/${patient.id.value}/encounters") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"classConceptId":"${classConcept.id.value}","periodStart":"$start"}"""
            header("Authorization", "Bearer ${member.token}")
            header("X-Correlation-Id", correlationId)
        }.andExpect {
            status { isForbidden() }
        }

        assertEquals(0, encounterCount(member.organization))
        val audit = auditRow(correlationId)
        assertEquals("AUTHORIZATION_DENIED", audit.operation)
        assertEquals("INSUFFICIENT_ROLE", audit.policyReasonCode)
    }

    @Test
    fun `opening an encounter for another organizations patient returns 404`() {
        val correlationId = "encounter-open-cross-${UUID.randomUUID()}"
        val member = createMember(MembershipRole.CLINICIAN, "user/Encounter.write")
        val otherOrganization = createOrganization()
        val otherPatient = createPatient(otherOrganization)

        mockMvc.post("/api/v1/patients/${otherPatient.id.value}/encounters") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"classConceptId":"${classConcept.id.value}","periodStart":"$start"}"""
            header("Authorization", "Bearer ${member.token}")
            header("X-Correlation-Id", correlationId)
        }.andExpect {
            status { isNotFound() }
        }

        assertEquals(0, encounterCount(member.organization))
        assertEquals(0, encounterCount(otherOrganization))
    }

    @Test
    fun `opening an encounter with terminal initial status returns 400`() {
        val member = createMember(MembershipRole.CLINICIAN, "user/Encounter.write")
        val patient = createPatient(member.organization)

        mockMvc.post("/api/v1/patients/${patient.id.value}/encounters") {
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "classConceptId": "${classConcept.id.value}",
                  "periodStart": "$start",
                  "status": "FINISHED"
                }
            """.trimIndent()
            header("Authorization", "Bearer ${member.token}")
        }.andExpect {
            status { isBadRequest() }
        }

        assertEquals(0, encounterCount(member.organization))
    }

    @Test
    fun `opening an encounter with an unknown class concept returns 400`() {
        val member = createMember(MembershipRole.CLINICIAN, "user/Encounter.write")
        val patient = createPatient(member.organization)

        mockMvc.post("/api/v1/patients/${patient.id.value}/encounters") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"classConceptId":"${UUID.randomUUID()}","periodStart":"$start"}"""
            header("Authorization", "Bearer ${member.token}")
        }.andExpect {
            status { isBadRequest() }
        }

        assertEquals(0, encounterCount(member.organization))
    }

    @Test
    fun `clinician and staff can read an encounter and reads are audited`() {
        listOf(
            MembershipRole.CLINICIAN,
            MembershipRole.STAFF,
        ).forEach { role ->
            val correlationId = "encounter-read-$role-${UUID.randomUUID()}"
            val member = createMember(role, "user/Encounter.read")
            val patient = createPatient(member.organization)
            val encounter = createEncounter(member.organization, patient)

            mockMvc.get("/api/v1/encounters/${encounter.id.value}") {
                header("Authorization", "Bearer ${member.token}")
                header("X-Correlation-Id", correlationId)
            }.andExpect {
                status { isOk() }
                jsonPath("$.id") { value(encounter.id.value.toString()) }
                jsonPath("$.patientId") { value(patient.id.value.toString()) }
                jsonPath("$.status") { value("planned") }
            }

            val audit = auditRow(correlationId)
            assertEquals("READ", audit.operation)
            assertEquals("SUCCESS", audit.outcome)
            assertEquals(encounter.id.value.toString(), audit.resourceId)
            assertEquals(patient.id.value.toString(), audit.patientId)
        }
    }

    @Test
    fun `cross organization encounter read returns 404 and audits a failed read`() {
        val correlationId = "encounter-read-cross-${UUID.randomUUID()}"
        val member = createMember(MembershipRole.CLINICIAN, "user/Encounter.read")
        val otherOrganization = createOrganization()
        val otherPatient = createPatient(otherOrganization)
        val otherEncounter = createEncounter(otherOrganization, otherPatient)

        mockMvc.get("/api/v1/encounters/${otherEncounter.id.value}") {
            header("Authorization", "Bearer ${member.token}")
            header("X-Correlation-Id", correlationId)
        }.andExpect {
            status { isNotFound() }
        }

        val audit = auditRow(correlationId)
        assertEquals("READ", audit.operation)
        assertEquals("FAILURE", audit.outcome)
        assertEquals(otherEncounter.id.value.toString(), audit.resourceId)
        assertEquals(null, audit.patientId)
    }

    @Test
    fun `patient encounter timeline is newest first and audited as a search`() {
        val correlationId = "encounter-timeline-${UUID.randomUUID()}"
        val member = createMember(MembershipRole.STAFF, "user/Encounter.read")
        val patient = createPatient(member.organization)
        val earlier = createEncounter(member.organization, patient, periodStart = Instant.parse(start))
        val later = createEncounter(
            member.organization,
            patient,
            periodStart = Instant.parse(start).plusSeconds(86_400),
        )

        mockMvc.get("/api/v1/patients/${patient.id.value}/encounters") {
            header("Authorization", "Bearer ${member.token}")
            header("X-Correlation-Id", correlationId)
        }.andExpect {
            status { isOk() }
            jsonPath("$.encounters.length()") { value(2) }
            jsonPath("$.encounters[0].id") { value(later.id.value.toString()) }
            jsonPath("$.encounters[1].id") { value(earlier.id.value.toString()) }
        }

        val audit = auditRow(correlationId)
        assertEquals("SEARCH", audit.operation)
        assertEquals("SUCCESS", audit.outcome)
        assertEquals(patient.id.value.toString(), audit.patientId)
    }

    @Test
    fun `timeline for another organizations patient returns 404`() {
        val correlationId = "encounter-timeline-cross-${UUID.randomUUID()}"
        val member = createMember(MembershipRole.STAFF, "user/Encounter.read")
        val otherOrganization = createOrganization()
        val otherPatient = createPatient(otherOrganization)
        createEncounter(otherOrganization, otherPatient)

        mockMvc.get("/api/v1/patients/${otherPatient.id.value}/encounters") {
            header("Authorization", "Bearer ${member.token}")
            header("X-Correlation-Id", correlationId)
        }.andExpect {
            status { isNotFound() }
        }

        val audit = auditRow(correlationId)
        assertEquals("SEARCH", audit.operation)
        assertEquals("FAILURE", audit.outcome)
    }

    @Test
    fun `clinician can progress and finish an encounter with audited updates`() {
        val progressCorrelationId = "encounter-progress-${UUID.randomUUID()}"
        val finishCorrelationId = "encounter-finish-${UUID.randomUUID()}"
        val member = createMember(MembershipRole.CLINICIAN, "user/Encounter.write user/Encounter.read")
        val patient = createPatient(member.organization)
        val encounter = createEncounter(member.organization, patient)

        mockMvc.post("/api/v1/encounters/${encounter.id.value}/status") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"targetStatus":"IN_PROGRESS","expectedVersion":1}"""
            header("Authorization", "Bearer ${member.token}")
            header("X-Correlation-Id", progressCorrelationId)
        }.andExpect {
            status { isOk() }
            jsonPath("$.status") { value("in-progress") }
            jsonPath("$.version") { value(2) }
        }

        val progressAudit = auditRow(progressCorrelationId)
        assertEquals("UPDATE", progressAudit.operation)
        assertEquals("SUCCESS", progressAudit.outcome)
        assertEquals(patient.id.value.toString(), progressAudit.patientId)

        val end = "2026-06-01T11:00:00Z"
        mockMvc.post("/api/v1/encounters/${encounter.id.value}/status") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"targetStatus":"FINISHED","periodEnd":"$end","expectedVersion":2}"""
            header("Authorization", "Bearer ${member.token}")
            header("X-Correlation-Id", finishCorrelationId)
        }.andExpect {
            status { isOk() }
            jsonPath("$.status") { value("finished") }
            jsonPath("$.periodEnd") { value(end) }
            jsonPath("$.version") { value(3) }
        }

        val finishAudit = auditRow(finishCorrelationId)
        assertEquals("UPDATE", finishAudit.operation)
        assertEquals("SUCCESS", finishAudit.outcome)
    }

    @Test
    fun `invalid transition returns 422 and audits a failed update`() {
        val correlationId = "encounter-invalid-${UUID.randomUUID()}"
        val member = createMember(MembershipRole.CLINICIAN, "user/Encounter.write")
        val patient = createPatient(member.organization)
        val encounter = createEncounter(member.organization, patient)

        mockMvc.post("/api/v1/encounters/${encounter.id.value}/status") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"targetStatus":"FINISHED","periodEnd":"2026-06-01T11:00:00Z"}"""
            header("Authorization", "Bearer ${member.token}")
            header("X-Correlation-Id", correlationId)
        }.andExpect {
            status { isUnprocessableEntity() }
        }

        val audit = auditRow(correlationId)
        assertEquals("UPDATE", audit.operation)
        assertEquals("FAILURE", audit.outcome)

        val unchanged = encounterRepository.findById(TenantScope(member.organization.id), encounter.id)!!
        assertEquals(EncounterStatus.PLANNED, unchanged.status)
        assertEquals(1, unchanged.version)
    }

    @Test
    fun `stale expected version returns 409 and audits a failed update`() {
        val correlationId = "encounter-stale-${UUID.randomUUID()}"
        val member = createMember(MembershipRole.CLINICIAN, "user/Encounter.write")
        val patient = createPatient(member.organization)
        val encounter = createEncounter(member.organization, patient)
        encounterRepository.transition(
            TenantScope(member.organization.id),
            encounter.id,
            EncounterTransitionCommand(targetStatus = EncounterStatus.IN_PROGRESS),
        )

        mockMvc.post("/api/v1/encounters/${encounter.id.value}/status") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"targetStatus":"FINISHED","periodEnd":"2026-06-01T11:00:00Z","expectedVersion":1}"""
            header("Authorization", "Bearer ${member.token}")
            header("X-Correlation-Id", correlationId)
        }.andExpect {
            status { isConflict() }
        }

        val audit = auditRow(correlationId)
        assertEquals("UPDATE", audit.operation)
        assertEquals("FAILURE", audit.outcome)
    }

    @Test
    fun `transition on missing encounter returns 404 and audits a failed update`() {
        val correlationId = "encounter-missing-${UUID.randomUUID()}"
        val member = createMember(MembershipRole.CLINICIAN, "user/Encounter.write")

        mockMvc.post("/api/v1/encounters/${UUID.randomUUID()}/status") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"targetStatus":"IN_PROGRESS"}"""
            header("Authorization", "Bearer ${member.token}")
            header("X-Correlation-Id", correlationId)
        }.andExpect {
            status { isNotFound() }
        }

        val audit = auditRow(correlationId)
        assertEquals("UPDATE", audit.operation)
        assertEquals("FAILURE", audit.outcome)
    }

    @Test
    fun `clinician without encounter write scope cannot transition`() {
        val correlationId = "encounter-scope-${UUID.randomUUID()}"
        val member = createMember(MembershipRole.CLINICIAN, "user/Encounter.read")
        val patient = createPatient(member.organization)
        val encounter = createEncounter(member.organization, patient)

        mockMvc.post("/api/v1/encounters/${encounter.id.value}/status") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"targetStatus":"IN_PROGRESS"}"""
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
            slug = "encounter-api-org-$suffix",
            displayName = "Encounter Api Org $suffix",
        )
    }

    private fun createMember(
        role: MembershipRole,
        scopes: String,
    ): EncounterMemberFixture {
        val suffix = UUID.randomUUID()
        val organization = createOrganization()
        val user = userRepository.create(
            externalSubject = "encounter-api-user-$suffix",
            email = "encounter-api-user-$suffix@example.test",
            displayName = "Encounter Api User $suffix",
        )
        val membership = membershipRepository.create(
            organizationId = organization.id,
            userId = user.id,
        )
        membershipRepository.addRole(membership.id, role)

        return EncounterMemberFixture(
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
        periodStart: Instant = Instant.parse(start),
    ): Encounter =
        encounterRepository.create(
            EncounterCreateCommand(
                organizationId = organization.id,
                patientId = patient.id,
                classConceptId = classConcept.id,
                periodStart = periodStart,
            ),
        )

    private fun encounterCount(organization: Organization): Int =
        jdbcTemplate.queryForObject(
            "select count(*) from encounters where organization_id = ?",
            Int::class.java,
            organization.id.value,
        )!!

    private fun auditCount(correlationId: String): Int =
        jdbcTemplate.queryForObject(
            "select count(*) from audit_events where correlation_id = ?",
            Int::class.java,
            correlationId,
        )!!

    private fun auditRow(correlationId: String): EncounterAuditRow {
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
                EncounterAuditRow(
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

data class EncounterMemberFixture(
    val organization: Organization,
    val user: User,
    val token: String,
)

data class EncounterAuditRow(
    val patientId: String?,
    val resourceType: String,
    val resourceId: String?,
    val operation: String,
    val outcome: String,
    val policyVersion: String?,
    val policyReasonCode: String?,
)
