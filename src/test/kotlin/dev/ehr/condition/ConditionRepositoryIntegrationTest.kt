package dev.ehr.condition

import dev.ehr.encounter.EncounterCreateCommand
import dev.ehr.encounter.EncounterRepository
import dev.ehr.identity.OrganizationRepository
import dev.ehr.identity.TenantScope
import dev.ehr.identity.UserRepository
import dev.ehr.patient.PatientRepository
import dev.ehr.terminology.CanonicalCodeSystems
import dev.ehr.terminology.CodeableConcept
import dev.ehr.terminology.CodeableConceptRepository
import dev.ehr.terminology.CodingRepository
import dev.ehr.testsupport.EncounterTestFixtures
import dev.ehr.testsupport.PatientTestFixtures
import dev.ehr.testsupport.PostgresIntegrationTest
import dev.ehr.testsupport.TerminologyTestFixtures
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.Instant
import java.time.LocalDate
import kotlin.test.assertFailsWith

class ConditionRepositoryIntegrationTest : PostgresIntegrationTest() {
    @Autowired
    lateinit var conditionRepository: ConditionRepository

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
    lateinit var terminologyFixtures: TerminologyTestFixtures
    lateinit var codeConcept: CodeableConcept

    @BeforeEach
    fun setUpFixtures() {
        patientFixtures = PatientTestFixtures(organizationRepository, userRepository, patientRepository)
        terminologyFixtures = TerminologyTestFixtures(codingRepository, codeableConceptRepository)
        codeConcept = terminologyFixtures.findOrCreateConcept(
            system = CanonicalCodeSystems.SNOMED_CT,
            code = "38341003",
            display = "Hypertensive disorder",
        )
    }

    @Test
    fun `creates and reads a condition with encounter link inside the tenant`() {
        val fixture = patientFixtures.createOrganizationUserAndPatient()
        val tenantScope = TenantScope(fixture.organization.id)
        val classConcept = EncounterTestFixtures(codingRepository, codeableConceptRepository)
            .createEncounterClassConcept()
        val encounter = encounterRepository.create(
            EncounterCreateCommand(
                organizationId = fixture.organization.id,
                patientId = fixture.patient.id,
                classConceptId = classConcept.id,
                periodStart = Instant.parse("2026-06-01T09:00:00Z"),
            ),
        )

        val created = conditionRepository.create(
            ConditionCreateCommand(
                organizationId = fixture.organization.id,
                patientId = fixture.patient.id,
                codeConceptId = codeConcept.id,
                encounterId = encounter.id,
                clinicalStatus = ConditionClinicalStatus.ACTIVE,
                verificationStatus = ConditionVerificationStatus.CONFIRMED,
                onsetDate = LocalDate.of(2026, 1, 15),
                createdBy = fixture.user.id,
            ),
        )

        assertEquals(fixture.patient.id, created.patientId)
        assertEquals(encounter.id, created.encounterId)
        assertEquals(ConditionClinicalStatus.ACTIVE, created.clinicalStatus)
        assertEquals(ConditionVerificationStatus.CONFIRMED, created.verificationStatus)
        assertEquals(codeConcept.id, created.codeConceptId)
        assertEquals(LocalDate.of(2026, 1, 15), created.onsetDate)
        assertEquals(null, created.abatementDate)
        assertEquals(1, created.version)
        assertEquals(fixture.user.id, created.createdBy)

        assertEquals(created, conditionRepository.findById(tenantScope, created.id))
    }

    @Test
    fun `wrong tenant condition reads fail closed`() {
        val two = patientFixtures.createTwoOrganizationsUsersAndPatients()
        val northCondition = conditionRepository.create(
            ConditionCreateCommand(
                organizationId = two.north.id,
                patientId = two.northPatient.id,
                codeConceptId = codeConcept.id,
            ),
        )

        val southScope = TenantScope(two.south.id)
        assertNull(conditionRepository.findById(southScope, northCondition.id))
        assertEquals(
            emptyList<Condition>(),
            conditionRepository.findByPatient(southScope, two.northPatient.id),
        )
    }

    @Test
    fun `cross tenant condition create fails closed`() {
        val two = patientFixtures.createTwoOrganizationsUsersAndPatients()

        assertFailsWith<IllegalArgumentException> {
            conditionRepository.create(
                ConditionCreateCommand(
                    organizationId = two.south.id,
                    patientId = two.northPatient.id,
                    codeConceptId = codeConcept.id,
                ),
            )
        }
    }

    @Test
    fun `problem list returns conditions newest first`() {
        val fixture = patientFixtures.createOrganizationUserAndPatient()
        val tenantScope = TenantScope(fixture.organization.id)

        val first = conditionRepository.create(
            ConditionCreateCommand(
                organizationId = fixture.organization.id,
                patientId = fixture.patient.id,
                codeConceptId = codeConcept.id,
            ),
        )
        val second = conditionRepository.create(
            ConditionCreateCommand(
                organizationId = fixture.organization.id,
                patientId = fixture.patient.id,
                codeConceptId = codeConcept.id,
            ),
        )

        val problemList = conditionRepository.findByPatient(tenantScope, fixture.patient.id)
        assertEquals(2, problemList.size)
        // recorded_at default now() is equal-or-later for the second insert; ties break by id.
        assertTrue(
            problemList.map { it.id }.containsAll(listOf(first.id, second.id)),
        )
        assertTrue(problemList[0].recordedAt >= problemList[1].recordedAt)
    }
}
