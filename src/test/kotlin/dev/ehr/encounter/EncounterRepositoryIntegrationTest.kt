package dev.ehr.encounter

import dev.ehr.identity.TenantScope
import dev.ehr.identity.OrganizationRepository
import dev.ehr.identity.UserRepository
import dev.ehr.patient.PatientRepository
import dev.ehr.terminology.CodeableConceptRepository
import dev.ehr.terminology.CodingRepository
import dev.ehr.testsupport.EncounterTestFixtures
import dev.ehr.testsupport.PatientTestFixtures
import dev.ehr.testsupport.PostgresIntegrationTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.Instant
import kotlin.test.assertFailsWith

class EncounterRepositoryIntegrationTest : PostgresIntegrationTest() {
    @Autowired
    lateinit var encounterRepository: EncounterRepository

    @Autowired
    lateinit var organizationRepository: OrganizationRepository

    @Autowired
    lateinit var userRepository: UserRepository

    @Autowired
    lateinit var patientRepository: PatientRepository

    @Autowired
    lateinit var codingRepository: CodingRepository

    @Autowired
    lateinit var codeableConceptRepository: CodeableConceptRepository

    lateinit var patientFixtures: PatientTestFixtures
    lateinit var encounterFixtures: EncounterTestFixtures

    private val start: Instant = Instant.parse("2026-06-01T09:00:00Z")

    @BeforeEach
    fun setUpFixtures() {
        patientFixtures = PatientTestFixtures(organizationRepository, userRepository, patientRepository)
        encounterFixtures = EncounterTestFixtures(codingRepository, codeableConceptRepository)
    }

    @Test
    fun `creates and reads an encounter inside the tenant`() {
        val fixture = patientFixtures.createOrganizationUserAndPatient()
        val classConcept = encounterFixtures.createEncounterClassConcept()
        val tenantScope = TenantScope(fixture.organization.id)

        val created = encounterRepository.create(
            EncounterCreateCommand(
                organizationId = fixture.organization.id,
                patientId = fixture.patient.id,
                classConceptId = classConcept.id,
                periodStart = start,
                status = EncounterStatus.IN_PROGRESS,
                createdBy = fixture.user.id,
            ),
        )

        assertEquals(fixture.organization.id, created.organizationId)
        assertEquals(fixture.patient.id, created.patientId)
        assertEquals(EncounterStatus.IN_PROGRESS, created.status)
        assertEquals(classConcept.id, created.classConceptId)
        assertEquals(start, created.periodStart)
        assertEquals(null, created.periodEnd)
        assertEquals(1, created.version)
        assertEquals(fixture.user.id, created.createdBy)

        val found = encounterRepository.findById(tenantScope, created.id)
        assertEquals(created, found)
    }

    @Test
    fun `wrong tenant encounter read returns null and timeline returns empty`() {
        val two = patientFixtures.createTwoOrganizationsUsersAndPatients()
        val classConcept = encounterFixtures.createEncounterClassConcept()
        val northEncounter = encounterRepository.create(
            EncounterCreateCommand(
                organizationId = two.north.id,
                patientId = two.northPatient.id,
                classConceptId = classConcept.id,
                periodStart = start,
            ),
        )

        val southScope = TenantScope(two.south.id)
        assertNull(encounterRepository.findById(southScope, northEncounter.id))
        assertEquals(emptyList<Encounter>(), encounterRepository.findByPatient(southScope, two.northPatient.id))
    }

    @Test
    fun `cross tenant encounter create fails closed`() {
        val two = patientFixtures.createTwoOrganizationsUsersAndPatients()
        val classConcept = encounterFixtures.createEncounterClassConcept()

        assertFailsWith<IllegalArgumentException> {
            encounterRepository.create(
                EncounterCreateCommand(
                    organizationId = two.south.id,
                    patientId = two.northPatient.id,
                    classConceptId = classConcept.id,
                    periodStart = start,
                ),
            )
        }
    }

    @Test
    fun `encounter create rejects terminal initial statuses`() {
        val fixture = patientFixtures.createOrganizationUserAndPatient()
        val classConcept = encounterFixtures.createEncounterClassConcept()

        listOf(
            EncounterStatus.FINISHED,
            EncounterStatus.CANCELLED,
            EncounterStatus.ENTERED_IN_ERROR,
        ).forEach { status ->
            assertFailsWith<IllegalArgumentException> {
                encounterRepository.create(
                    EncounterCreateCommand(
                        organizationId = fixture.organization.id,
                        patientId = fixture.patient.id,
                        classConceptId = classConcept.id,
                        periodStart = start,
                        status = status,
                    ),
                )
            }
        }
    }

    @Test
    fun `patient timeline returns encounters newest first`() {
        val fixture = patientFixtures.createOrganizationUserAndPatient()
        val classConcept = encounterFixtures.createEncounterClassConcept()
        val tenantScope = TenantScope(fixture.organization.id)

        val earlier = encounterRepository.create(
            EncounterCreateCommand(
                organizationId = fixture.organization.id,
                patientId = fixture.patient.id,
                classConceptId = classConcept.id,
                periodStart = start,
            ),
        )
        val later = encounterRepository.create(
            EncounterCreateCommand(
                organizationId = fixture.organization.id,
                patientId = fixture.patient.id,
                classConceptId = classConcept.id,
                periodStart = start.plusSeconds(86_400),
            ),
        )

        val timeline = encounterRepository.findByPatient(tenantScope, fixture.patient.id)
        assertEquals(listOf(later.id, earlier.id), timeline.map { it.id })
    }

    @Test
    fun `planned encounter can progress and finish with a period end`() {
        val fixture = patientFixtures.createOrganizationUserAndPatient()
        val classConcept = encounterFixtures.createEncounterClassConcept()
        val tenantScope = TenantScope(fixture.organization.id)
        val created = encounterRepository.create(
            EncounterCreateCommand(
                organizationId = fixture.organization.id,
                patientId = fixture.patient.id,
                classConceptId = classConcept.id,
                periodStart = start,
                createdBy = fixture.user.id,
            ),
        )

        val inProgress = encounterRepository.transition(
            tenantScope,
            created.id,
            EncounterTransitionCommand(targetStatus = EncounterStatus.IN_PROGRESS, updatedBy = fixture.user.id, expectedVersion = 1),
        )!!
        assertEquals(EncounterStatus.IN_PROGRESS, inProgress.status)
        assertEquals(2, inProgress.version)
        assertEquals(fixture.user.id, inProgress.updatedBy)

        val end = start.plusSeconds(7_200)
        val finished = encounterRepository.transition(
            tenantScope,
            created.id,
            EncounterTransitionCommand(targetStatus = EncounterStatus.FINISHED, periodEnd = end, expectedVersion = 2),
        )!!
        assertEquals(EncounterStatus.FINISHED, finished.status)
        assertEquals(end, finished.periodEnd)
        assertEquals(3, finished.version)
    }

    @Test
    fun `finishing without a period end is rejected`() {
        val fixture = patientFixtures.createOrganizationUserAndPatient()
        val classConcept = encounterFixtures.createEncounterClassConcept()
        val tenantScope = TenantScope(fixture.organization.id)
        val created = encounterRepository.create(
            EncounterCreateCommand(
                organizationId = fixture.organization.id,
                patientId = fixture.patient.id,
                classConceptId = classConcept.id,
                periodStart = start,
                status = EncounterStatus.IN_PROGRESS,
            ),
        )

        assertFailsWith<IllegalArgumentException> {
            encounterRepository.transition(
                tenantScope,
                created.id,
                EncounterTransitionCommand(targetStatus = EncounterStatus.FINISHED, expectedVersion = 1),
            )
        }
    }

    @Test
    fun `invalid transitions are rejected before touching the database`() {
        val fixture = patientFixtures.createOrganizationUserAndPatient()
        val classConcept = encounterFixtures.createEncounterClassConcept()
        val tenantScope = TenantScope(fixture.organization.id)

        // planned cannot finish directly
        val planned = encounterRepository.create(
            EncounterCreateCommand(
                organizationId = fixture.organization.id,
                patientId = fixture.patient.id,
                classConceptId = classConcept.id,
                periodStart = start,
            ),
        )
        assertFailsWith<IllegalArgumentException> {
            encounterRepository.transition(
                tenantScope,
                planned.id,
                EncounterTransitionCommand(targetStatus = EncounterStatus.FINISHED, periodEnd = start.plusSeconds(60), expectedVersion = 1),
            )
        }

        // entered-in-error is terminal
        val voided = encounterRepository.transition(
            tenantScope,
            planned.id,
            EncounterTransitionCommand(targetStatus = EncounterStatus.ENTERED_IN_ERROR, expectedVersion = 1),
        )!!
        assertEquals(EncounterStatus.ENTERED_IN_ERROR, voided.status)
        assertFailsWith<IllegalArgumentException> {
            encounterRepository.transition(
                tenantScope,
                planned.id,
                EncounterTransitionCommand(targetStatus = EncounterStatus.IN_PROGRESS, expectedVersion = 2),
            )
        }
    }

    @Test
    fun `stale expected version transition fails without modifying the encounter`() {
        val fixture = patientFixtures.createOrganizationUserAndPatient()
        val classConcept = encounterFixtures.createEncounterClassConcept()
        val tenantScope = TenantScope(fixture.organization.id)
        val created = encounterRepository.create(
            EncounterCreateCommand(
                organizationId = fixture.organization.id,
                patientId = fixture.patient.id,
                classConceptId = classConcept.id,
                periodStart = start,
            ),
        )

        encounterRepository.transition(
            tenantScope,
            created.id,
            EncounterTransitionCommand(targetStatus = EncounterStatus.IN_PROGRESS, expectedVersion = 1),
        )

        assertFailsWith<StaleEncounterTransitionException> {
            encounterRepository.transition(
                tenantScope,
                created.id,
                EncounterTransitionCommand(
                    targetStatus = EncounterStatus.FINISHED,
                    periodEnd = start.plusSeconds(60),
                    expectedVersion = 1,
                ),
            )
        }

        val unchanged = encounterRepository.findById(tenantScope, created.id)!!
        assertEquals(EncounterStatus.IN_PROGRESS, unchanged.status)
        assertEquals(2, unchanged.version)
    }

    @Test
    fun `wrong tenant transition returns null without modifying the encounter`() {
        val two = patientFixtures.createTwoOrganizationsUsersAndPatients()
        val classConcept = encounterFixtures.createEncounterClassConcept()
        val northEncounter = encounterRepository.create(
            EncounterCreateCommand(
                organizationId = two.north.id,
                patientId = two.northPatient.id,
                classConceptId = classConcept.id,
                periodStart = start,
            ),
        )

        val result = encounterRepository.transition(
            TenantScope(two.south.id),
            northEncounter.id,
            EncounterTransitionCommand(targetStatus = EncounterStatus.IN_PROGRESS, expectedVersion = 1),
        )
        assertNull(result)

        val unchanged = encounterRepository.findById(TenantScope(two.north.id), northEncounter.id)!!
        assertEquals(EncounterStatus.PLANNED, unchanged.status)
        assertEquals(1, unchanged.version)
    }
}
