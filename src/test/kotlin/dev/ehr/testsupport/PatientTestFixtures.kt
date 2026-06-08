package dev.ehr.testsupport

import dev.ehr.identity.Organization
import dev.ehr.identity.OrganizationRepository
import dev.ehr.identity.User
import dev.ehr.identity.UserRepository
import dev.ehr.patient.Patient
import dev.ehr.patient.PatientAdministrativeGender
import dev.ehr.patient.PatientCreateCommand
import dev.ehr.patient.PatientRepository
import java.time.LocalDate
import java.util.UUID

class PatientTestFixtures(
    private val organizationRepository: OrganizationRepository,
    private val userRepository: UserRepository,
    private val patientRepository: PatientRepository,
) {
    fun createOrganizationUserAndPatient(): PatientFixture {
        val suffix = UUID.randomUUID()
        val organization = organizationRepository.create(
            slug = "patient-org-$suffix",
            displayName = "Patient Org $suffix",
        )
        val user = userRepository.create(
            externalSubject = "patient-user-$suffix",
            email = "patient-user-$suffix@example.test",
            displayName = "Patient User $suffix",
        )
        val patient = patientRepository.create(
            PatientCreateCommand(
                organizationId = organization.id,
                givenName = "Synthetic",
                familyName = "Patient",
                birthDate = LocalDate.of(2000, 1, 1),
                administrativeGender = PatientAdministrativeGender.UNKNOWN,
                createdBy = user.id,
            ),
        )

        return PatientFixture(
            organization = organization,
            user = user,
            patient = patient,
        )
    }

    fun createTwoOrganizationsUsersAndPatients(): TwoPatientFixture {
        val north = createOrganizationUserAndPatient()
        val south = createOrganizationUserAndPatient()

        return TwoPatientFixture(
            north = north.organization,
            south = south.organization,
            northUser = north.user,
            southUser = south.user,
            northPatient = north.patient,
            southPatient = south.patient,
        )
    }
}

data class PatientFixture(
    val organization: Organization,
    val user: User,
    val patient: Patient,
)

data class TwoPatientFixture(
    val north: Organization,
    val south: Organization,
    val northUser: User,
    val southUser: User,
    val northPatient: Patient,
    val southPatient: Patient,
)
