package dev.ehr.allergy

import dev.ehr.identity.OrganizationRepository
import dev.ehr.identity.TenantScope
import dev.ehr.identity.UserRepository
import dev.ehr.patient.PatientRepository
import dev.ehr.terminology.CanonicalCodeSystems
import dev.ehr.terminology.CodeableConcept
import dev.ehr.terminology.CodeableConceptRepository
import dev.ehr.terminology.CodingRepository
import dev.ehr.testsupport.PatientTestFixtures
import dev.ehr.testsupport.PostgresIntegrationTest
import dev.ehr.testsupport.TerminologyTestFixtures
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.LocalDate
import kotlin.test.assertFailsWith

class AllergyRepositoryIntegrationTest : PostgresIntegrationTest() {
    @Autowired
    lateinit var allergyRepository: AllergyRepository

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
    lateinit var codeConcept: CodeableConcept

    @BeforeEach
    fun setUpFixtures() {
        patientFixtures = PatientTestFixtures(organizationRepository, userRepository, patientRepository)
        codeConcept = TerminologyTestFixtures(codingRepository, codeableConceptRepository)
            .findOrCreateConcept(
                system = CanonicalCodeSystems.SNOMED_CT,
                code = "91935009",
                display = "Allergy to peanut",
            )
    }

    @Test
    fun `creates and reads an allergy with all coded fields inside the tenant`() {
        val fixture = patientFixtures.createOrganizationUserAndPatient()
        val tenantScope = TenantScope(fixture.organization.id)

        val created = allergyRepository.create(
            AllergyCreateCommand(
                organizationId = fixture.organization.id,
                patientId = fixture.patient.id,
                codeConceptId = codeConcept.id,
                clinicalStatus = AllergyClinicalStatus.ACTIVE,
                verificationStatus = AllergyVerificationStatus.CONFIRMED,
                category = AllergyCategory.FOOD,
                criticality = AllergyCriticality.HIGH,
                onsetDate = LocalDate.of(2020, 7, 4),
                createdBy = fixture.user.id,
            ),
        )

        assertEquals(fixture.patient.id, created.patientId)
        assertEquals(AllergyCategory.FOOD, created.category)
        assertEquals(AllergyCriticality.HIGH, created.criticality)
        assertEquals(LocalDate.of(2020, 7, 4), created.onsetDate)
        assertEquals(1, created.version)
        assertEquals(created, allergyRepository.findById(tenantScope, created.id))
    }

    @Test
    fun `wrong tenant allergy reads fail closed`() {
        val two = patientFixtures.createTwoOrganizationsUsersAndPatients()
        val northAllergy = allergyRepository.create(
            AllergyCreateCommand(
                organizationId = two.north.id,
                patientId = two.northPatient.id,
                codeConceptId = codeConcept.id,
            ),
        )

        val southScope = TenantScope(two.south.id)
        assertNull(allergyRepository.findById(southScope, northAllergy.id))
        assertEquals(
            emptyList<Allergy>(),
            allergyRepository.findByPatient(southScope, two.northPatient.id),
        )
    }

    @Test
    fun `cross tenant allergy create fails closed`() {
        val two = patientFixtures.createTwoOrganizationsUsersAndPatients()

        assertFailsWith<IllegalArgumentException> {
            allergyRepository.create(
                AllergyCreateCommand(
                    organizationId = two.south.id,
                    patientId = two.northPatient.id,
                    codeConceptId = codeConcept.id,
                ),
            )
        }
    }

    @Test
    fun `allergy list returns newest first`() {
        val fixture = patientFixtures.createOrganizationUserAndPatient()
        val tenantScope = TenantScope(fixture.organization.id)

        val first = allergyRepository.create(
            AllergyCreateCommand(
                organizationId = fixture.organization.id,
                patientId = fixture.patient.id,
                codeConceptId = codeConcept.id,
            ),
        )
        val second = allergyRepository.create(
            AllergyCreateCommand(
                organizationId = fixture.organization.id,
                patientId = fixture.patient.id,
                codeConceptId = codeConcept.id,
            ),
        )

        val allergyList = allergyRepository.findByPatient(tenantScope, fixture.patient.id)
        assertEquals(2, allergyList.size)
        assertTrue(allergyList.map { it.id }.containsAll(listOf(first.id, second.id)))
        assertTrue(allergyList[0].recordedAt >= allergyList[1].recordedAt)
    }
}
