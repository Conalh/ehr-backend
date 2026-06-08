package dev.ehr.testsupport

import dev.ehr.identity.MembershipRepository
import dev.ehr.identity.MembershipRole
import dev.ehr.identity.Organization
import dev.ehr.identity.OrganizationRepository
import dev.ehr.identity.User
import dev.ehr.identity.UserRepository
import java.util.UUID

class IdentityTestFixtures(
    private val organizationRepository: OrganizationRepository,
    private val userRepository: UserRepository,
    private val membershipRepository: MembershipRepository,
) {
    fun createTwoOrganizationsUsersAndMemberships(): IdentityFixtureGraph {
        val suffix = UUID.randomUUID().toString()
        val north = organizationRepository.create(
            slug = "north-$suffix",
            displayName = "North Clinic $suffix",
        )
        val south = organizationRepository.create(
            slug = "south-$suffix",
            displayName = "South Clinic $suffix",
        )
        val clinician = userRepository.create(
            externalSubject = "clinician-$suffix",
            email = "clinician-$suffix@example.test",
            displayName = "Clinician $suffix",
        )
        val staff = userRepository.create(
            externalSubject = "staff-$suffix",
            email = "staff-$suffix@example.test",
            displayName = "Staff $suffix",
        )

        val northMembership = membershipRepository.create(
            organizationId = north.id,
            userId = clinician.id,
        )
        membershipRepository.addRole(northMembership.id, MembershipRole.CLINICIAN)

        val southMembership = membershipRepository.create(
            organizationId = south.id,
            userId = staff.id,
        )
        membershipRepository.addRole(southMembership.id, MembershipRole.STAFF)

        return IdentityFixtureGraph(
            north = north,
            south = south,
            clinician = clinician,
            staff = staff,
        )
    }
}

data class IdentityFixtureGraph(
    val north: Organization,
    val south: Organization,
    val clinician: User,
    val staff: User,
)
