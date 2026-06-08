package dev.ehr.patient

import dev.ehr.identity.OrganizationRepository
import dev.ehr.identity.TenantScope
import dev.ehr.identity.UserRepository
import dev.ehr.terminology.BindingContext
import dev.ehr.terminology.CanonicalCodeSystems
import dev.ehr.terminology.CodeableConceptRepository
import dev.ehr.terminology.CodingRepository
import dev.ehr.testsupport.PatientTestFixtures
import dev.ehr.testsupport.PostgresIntegrationTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataAccessException
import java.time.LocalDate
import java.util.UUID
import kotlin.test.assertFailsWith

class PatientRepositoryIntegrationTest : PostgresIntegrationTest() {
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

    @Test
    fun `patients can be created and read inside a tenant`() {
        val fixture = PatientTestFixtures(
            organizationRepository = organizationRepository,
            userRepository = userRepository,
            patientRepository = patientRepository,
        ).createOrganizationUserAndPatient()

        assertNotNull(fixture.patient.id)
        assertEquals(fixture.organization.id, fixture.patient.organizationId)
        assertEquals(PatientStatus.ACTIVE, fixture.patient.status)
        assertEquals(1, fixture.patient.version)
        assertEquals(fixture.user.id, fixture.patient.createdBy)
        assertEquals(
            fixture.patient,
            patientRepository.findById(TenantScope(fixture.organization.id), fixture.patient.id),
        )
    }

    @Test
    fun `wrong tenant patient reads return null`() {
        val fixture = PatientTestFixtures(
            organizationRepository = organizationRepository,
            userRepository = userRepository,
            patientRepository = patientRepository,
        ).createTwoOrganizationsUsersAndPatients()

        assertEquals(
            null,
            patientRepository.findById(TenantScope(fixture.south.id), fixture.northPatient.id),
        )
    }

    @Test
    fun `identifiers can be added and used to find patients inside a tenant`() {
        val fixture = PatientTestFixtures(
            organizationRepository = organizationRepository,
            userRepository = userRepository,
            patientRepository = patientRepository,
        ).createOrganizationUserAndPatient()
        val tenantScope = TenantScope(fixture.organization.id)

        val identifier = patientRepository.addIdentifier(
            tenantScope = tenantScope,
            patientId = fixture.patient.id,
            command = PatientIdentifierCreateCommand(
                system = "https://example.test/mrn",
                value = "MRN-${UUID.randomUUID()}",
                use = IdentifierUse.OFFICIAL,
                assignerText = "Synthetic Test Registry",
                periodStart = LocalDate.of(2026, 1, 1),
            ),
        )

        assertEquals(fixture.organization.id, identifier.organizationId)
        assertEquals(fixture.patient.id, identifier.patientId)
        assertEquals(IdentifierUse.OFFICIAL, identifier.use)
        assertEquals(listOf(identifier), patientRepository.findIdentifiers(tenantScope, fixture.patient.id))
        assertEquals(
            fixture.patient,
            patientRepository.findByIdentifier(tenantScope, identifier.system, identifier.value),
        )
    }

    @Test
    fun `wrong tenant identifier lookup returns null`() {
        val fixture = PatientTestFixtures(
            organizationRepository = organizationRepository,
            userRepository = userRepository,
            patientRepository = patientRepository,
        ).createTwoOrganizationsUsersAndPatients()
        val identifier = patientRepository.addIdentifier(
            tenantScope = TenantScope(fixture.north.id),
            patientId = fixture.northPatient.id,
            command = PatientIdentifierCreateCommand(
                system = "https://example.test/mrn",
                value = "MRN-${UUID.randomUUID()}",
                use = IdentifierUse.OFFICIAL,
            ),
        )

        assertEquals(
            null,
            patientRepository.findByIdentifier(TenantScope(fixture.south.id), identifier.system, identifier.value),
        )
        assertEquals(
            emptyList<PatientIdentifier>(),
            patientRepository.findIdentifiers(TenantScope(fixture.south.id), fixture.northPatient.id),
        )
    }

    @Test
    fun `duplicate identifier in same organization fails`() {
        val fixture = PatientTestFixtures(
            organizationRepository = organizationRepository,
            userRepository = userRepository,
            patientRepository = patientRepository,
        ).createOrganizationUserAndPatient()
        val tenantScope = TenantScope(fixture.organization.id)
        val command = PatientIdentifierCreateCommand(
            system = "https://example.test/mrn",
            value = "MRN-${UUID.randomUUID()}",
            use = IdentifierUse.OFFICIAL,
        )
        patientRepository.addIdentifier(tenantScope, fixture.patient.id, command)

        assertFailsWith<DataAccessException> {
            patientRepository.addIdentifier(tenantScope, fixture.patient.id, command)
        }
    }

    @Test
    fun `same identifier in different organizations is allowed`() {
        val fixture = PatientTestFixtures(
            organizationRepository = organizationRepository,
            userRepository = userRepository,
            patientRepository = patientRepository,
        ).createTwoOrganizationsUsersAndPatients()
        val sharedValue = "MRN-${UUID.randomUUID()}"

        val northIdentifier = patientRepository.addIdentifier(
            tenantScope = TenantScope(fixture.north.id),
            patientId = fixture.northPatient.id,
            command = PatientIdentifierCreateCommand(
                system = "https://example.test/mrn",
                value = sharedValue,
                use = IdentifierUse.OFFICIAL,
            ),
        )
        val southIdentifier = patientRepository.addIdentifier(
            tenantScope = TenantScope(fixture.south.id),
            patientId = fixture.southPatient.id,
            command = PatientIdentifierCreateCommand(
                system = "https://example.test/mrn",
                value = sharedValue,
                use = IdentifierUse.OFFICIAL,
            ),
        )

        assertEquals(sharedValue, northIdentifier.value)
        assertEquals(sharedValue, southIdentifier.value)
        assertEquals(fixture.northPatient, patientRepository.findByIdentifier(TenantScope(fixture.north.id), northIdentifier.system, sharedValue))
        assertEquals(fixture.southPatient, patientRepository.findByIdentifier(TenantScope(fixture.south.id), southIdentifier.system, sharedValue))
    }

    @Test
    fun `identifier type can reference terminology codeable concept`() {
        val fixture = PatientTestFixtures(
            organizationRepository = organizationRepository,
            userRepository = userRepository,
            patientRepository = patientRepository,
        ).createOrganizationUserAndPatient()
        val coding = codingRepository.create(
            system = CanonicalCodeSystems.HL7_V3_ACT_CODE,
            code = "MR",
            display = "Medical record number",
        )
        val identifierType = codeableConceptRepository.create(
            text = "Medical record number",
            bindingContext = BindingContext("Patient.identifier.type"),
            codingIds = listOf(coding.id),
            primaryCodingId = coding.id,
        )

        val identifier = patientRepository.addIdentifier(
            tenantScope = TenantScope(fixture.organization.id),
            patientId = fixture.patient.id,
            command = PatientIdentifierCreateCommand(
                system = "https://example.test/mrn",
                value = "MRN-${UUID.randomUUID()}",
                use = IdentifierUse.OFFICIAL,
                typeConceptId = identifierType.id,
            ),
        )

        assertEquals(identifierType.id, identifier.typeConceptId)
        assertEquals(
            identifier,
            patientRepository.findIdentifiers(TenantScope(fixture.organization.id), fixture.patient.id).single(),
        )
    }
}
