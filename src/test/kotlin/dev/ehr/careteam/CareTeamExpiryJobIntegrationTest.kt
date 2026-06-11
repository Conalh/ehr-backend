package dev.ehr.careteam

import dev.ehr.identity.MembershipRepository
import dev.ehr.identity.MembershipRole
import dev.ehr.identity.Organization
import dev.ehr.identity.OrganizationRepository
import dev.ehr.identity.TenantScope
import dev.ehr.identity.UserRepository
import dev.ehr.patient.Patient
import dev.ehr.patient.PatientCreateCommand
import dev.ehr.patient.PatientRepository
import dev.ehr.terminology.CodeableConceptRepository
import dev.ehr.terminology.CodingRepository
import dev.ehr.testsupport.DevJwtFactory
import dev.ehr.testsupport.DevJwtTestConfiguration
import dev.ehr.testsupport.EncounterTestFixtures
import dev.ehr.testsupport.PostgresIntegrationTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import java.util.UUID

/**
 * Slice H3: encounter-derived memberships auto-expire once their sustaining
 * encounter has been finished for longer than the configured window (default
 * 30 days). Open encounters sustain; explicit memberships never expire.
 */
@AutoConfigureMockMvc
@Import(DevJwtTestConfiguration::class)
class CareTeamExpiryJobIntegrationTest : PostgresIntegrationTest() {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var expiryJob: CareTeamExpiryJob

    @Autowired
    lateinit var careTeamRepository: CareTeamRepository

    @Autowired
    lateinit var organizationRepository: OrganizationRepository

    @Autowired
    lateinit var userRepository: UserRepository

    @Autowired
    lateinit var membershipRepository: MembershipRepository

    @Autowired
    lateinit var patientRepository: PatientRepository

    @Autowired
    lateinit var codingRepository: CodingRepository

    @Autowired
    lateinit var codeableConceptRepository: CodeableConceptRepository

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    lateinit var jwtEncoder: JwtEncoder

    @Test
    fun `memberships expire once the sustaining encounter is long finished`() {
        val fixture = createMember()
        val patient = createPatient(fixture.organization)
        val scope = TenantScope(fixture.organization.id)
        openEncounter(fixture, patient)

        // Sustained by the still-open encounter: the sweep keeps it.
        expiryJob.expireStale()
        assertEquals(1, careTeamRepository.findActiveByPatient(scope, patient.id).size)

        // Finish the encounter 40 days ago (test setup writes SQL directly).
        jdbcTemplate.update(
            """
            update encounters
            set status = 'finished',
                period_start = now() - interval '41 days',
                period_end = now() - interval '40 days'
            where organization_id = ? and patient_id = ?
            """.trimIndent(),
            fixture.organization.id.value,
            patient.id.value,
        )

        val ended = expiryJob.expireStale()
        assertEquals(1, ended)
        assertEquals(0, careTeamRepository.findActiveByPatient(scope, patient.id).size)

        // Each expiry leaves a SYSTEM audit trail.
        val membershipId = jdbcTemplate.queryForObject(
            """
            select id from care_team_memberships
            where organization_id = ? and patient_id = ? and period_end is not null
            """.trimIndent(),
            UUID::class.java,
            fixture.organization.id.value,
            patient.id.value,
        )
        val auditCount = jdbcTemplate.queryForObject(
            """
            select count(*) from audit_events
            where resource_type = 'CARE_TEAM' and operation = 'SYSTEM' and resource_id = ?
            """.trimIndent(),
            Int::class.java,
            membershipId,
        )
        assertEquals(1, auditCount)

        // A new encounter re-establishes the relationship (H1 idempotency
        // works against active rows only).
        openEncounter(fixture, patient)
        assertEquals(1, careTeamRepository.findActiveByPatient(scope, patient.id).size)
    }

    @Test
    fun `recently finished encounters and explicit memberships survive the sweep`() {
        val fixture = createMember()
        val patient = createPatient(fixture.organization)
        val scope = TenantScope(fixture.organization.id)
        openEncounter(fixture, patient)

        // Finished yesterday: inside the 30-day window.
        jdbcTemplate.update(
            """
            update encounters
            set status = 'finished',
                period_start = now() - interval '2 days',
                period_end = now() - interval '1 day'
            where organization_id = ? and patient_id = ?
            """.trimIndent(),
            fixture.organization.id.value,
            patient.id.value,
        )
        expiryJob.expireStale()
        assertEquals(1, careTeamRepository.findActiveByPatient(scope, patient.id).size)

        // Explicit memberships are never auto-expired, even with no encounter.
        val colleaguePatient = createPatient(fixture.organization)
        careTeamRepository.addMember(
            organizationId = fixture.organization.id,
            patientId = colleaguePatient.id,
            userId = fixture.userId,
            role = CareTeamRole.ATTENDING,
            origin = CareTeamMembershipOrigin.EXPLICIT,
            createdBy = fixture.userId,
        )
        expiryJob.expireStale()
        assertEquals(1, careTeamRepository.findActiveByPatient(scope, colleaguePatient.id).size)
    }

    private fun openEncounter(
        fixture: ExpiryMemberFixture,
        patient: Patient,
    ) {
        val classConcept = EncounterTestFixtures(codingRepository, codeableConceptRepository)
            .createEncounterClassConcept()
        mockMvc.post("/api/v1/patients/${patient.id.value}/encounters") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"classConceptId":"${classConcept.id.value}","periodStart":"2026-06-01T09:00:00Z"}"""
            header("Authorization", "Bearer ${fixture.token}")
        }.andExpect {
            status { isCreated() }
        }
    }

    private fun createMember(): ExpiryMemberFixture {
        val suffix = UUID.randomUUID()
        val organization = organizationRepository.create(
            slug = "expiry-org-$suffix",
            displayName = "Expiry Org $suffix",
        )
        val user = userRepository.create(
            externalSubject = "expiry-user-$suffix",
            email = "expiry-user-$suffix@example.test",
            displayName = "Expiry User $suffix",
        )
        val membership = membershipRepository.create(
            organizationId = organization.id,
            userId = user.id,
        )
        membershipRepository.addRole(membership.id, MembershipRole.CLINICIAN)

        return ExpiryMemberFixture(
            organization = organization,
            userId = user.id,
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
}

data class ExpiryMemberFixture(
    val organization: Organization,
    val userId: dev.ehr.identity.UserId,
    val token: String,
)
