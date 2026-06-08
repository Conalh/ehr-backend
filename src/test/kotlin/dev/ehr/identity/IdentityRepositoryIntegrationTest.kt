package dev.ehr.identity

import dev.ehr.testsupport.IdentityTestFixtures
import dev.ehr.testsupport.PostgresIntegrationTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID
import kotlin.test.assertFailsWith

class IdentityRepositoryIntegrationTest : PostgresIntegrationTest() {
    @Autowired
    lateinit var organizationRepository: OrganizationRepository

    @Autowired
    lateinit var userRepository: UserRepository

    @Autowired
    lateinit var practitionerRepository: PractitionerRepository

    @Autowired
    lateinit var membershipRepository: MembershipRepository

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Test
    fun `organizations can be created and read by id and slug`() {
        val suffix = UUID.randomUUID()
        val created = organizationRepository.create(
            slug = "org-$suffix",
            displayName = "Organization $suffix",
        )

        assertEquals(OrganizationStatus.ACTIVE, created.status)
        assertEquals(created, organizationRepository.findById(created.id))
        assertEquals(created, organizationRepository.findBySlug(created.slug))
    }

    @Test
    fun `users can be created and read by id and external subject`() {
        val suffix = UUID.randomUUID()
        val created = userRepository.create(
            externalSubject = "subject-$suffix",
            email = "subject-$suffix@example.test",
            displayName = "Subject $suffix",
        )

        assertEquals(UserStatus.ACTIVE, created.status)
        assertEquals(created, userRepository.findById(created.id))
        assertEquals(created, userRepository.findByExternalSubject(created.externalSubject))
    }

    @Test
    fun `practitioners can be created and read by id and user id`() {
        val suffix = UUID.randomUUID()
        val user = userRepository.create(
            externalSubject = "practitioner-$suffix",
            email = "practitioner-$suffix@example.test",
            displayName = "Practitioner User $suffix",
        )
        val practitioner = practitionerRepository.create(
            userId = user.id,
            displayName = "Practitioner $suffix",
            npi = "npi-$suffix",
        )

        assertEquals(PractitionerStatus.ACTIVE, practitioner.status)
        assertEquals(practitioner, practitionerRepository.findById(practitioner.id))
        assertEquals(practitioner, practitionerRepository.findByUserId(user.id))
    }

    @Test
    fun `memberships connect organizations users and roles`() {
        val fixtures = IdentityTestFixtures(
            organizationRepository = organizationRepository,
            userRepository = userRepository,
            membershipRepository = membershipRepository,
        ).createTwoOrganizationsUsersAndMemberships()

        val northMembership = membershipRepository.findByOrganizationAndUser(
            organizationId = fixtures.north.id,
            userId = fixtures.clinician.id,
        )
        val southMembership = membershipRepository.findByOrganizationAndUser(
            organizationId = fixtures.south.id,
            userId = fixtures.staff.id,
        )

        assertEquals(fixtures.north.id, northMembership?.organizationId)
        assertEquals(fixtures.clinician.id, northMembership?.userId)
        assertEquals(listOf(MembershipRole.CLINICIAN), membershipRepository.findRoles(northMembership!!.id))

        assertEquals(fixtures.south.id, southMembership?.organizationId)
        assertEquals(fixtures.staff.id, southMembership?.userId)
        assertEquals(listOf(MembershipRole.STAFF), membershipRepository.findRoles(southMembership!!.id))
    }

    @Test
    fun `cross org fixture data remains distinct through organization aware repository methods`() {
        val fixtures = IdentityTestFixtures(
            organizationRepository = organizationRepository,
            userRepository = userRepository,
            membershipRepository = membershipRepository,
        ).createTwoOrganizationsUsersAndMemberships()

        val northMemberships = membershipRepository.findByOrganizationId(fixtures.north.id)
        val southMemberships = membershipRepository.findByOrganizationId(fixtures.south.id)

        assertEquals(1, northMemberships.size)
        assertEquals(1, southMemberships.size)
        assertNotEquals(northMemberships.single().organizationId, southMemberships.single().organizationId)
        assertTrue(northMemberships.all { it.organizationId == fixtures.north.id })
        assertTrue(southMemberships.all { it.organizationId == fixtures.south.id })
    }

    @Test
    fun `duplicate organization slug and user subject constraints are enforced`() {
        val suffix = UUID.randomUUID()
        organizationRepository.create(
            slug = "dupe-org-$suffix",
            displayName = "Dupe Org $suffix",
        )
        userRepository.create(
            externalSubject = "dupe-subject-$suffix",
            email = "dupe-subject-$suffix@example.test",
            displayName = "Dupe User $suffix",
        )

        assertFailsWith<DataAccessException> {
            organizationRepository.create(
                slug = "dupe-org-$suffix",
                displayName = "Other Org $suffix",
            )
        }
        assertFailsWith<DataAccessException> {
            userRepository.create(
                externalSubject = "dupe-subject-$suffix",
                email = "other-dupe-subject-$suffix@example.test",
                displayName = "Other User $suffix",
            )
        }
    }

    @Test
    fun `invalid statuses and roles fail at the database layer`() {
        assertFailsWith<DataAccessException> {
            jdbcTemplate.update(
                "insert into organizations (slug, display_name, status) values (?, ?, ?)",
                "invalid-status-${UUID.randomUUID()}",
                "Invalid Status",
                "enabled",
            )
        }

        val suffix = UUID.randomUUID()
        assertFailsWith<DataAccessException> {
            jdbcTemplate.update(
                "insert into users (external_subject, email, display_name, status) values (?, ?, ?, ?)",
                "invalid-user-status-$suffix",
                "invalid-user-status-$suffix@example.test",
                "Invalid User Status",
                "enabled",
            )
        }

        val organization = organizationRepository.create(
            slug = "invalid-role-org-$suffix",
            displayName = "Invalid Role Org $suffix",
        )
        val user = userRepository.create(
            externalSubject = "invalid-role-user-$suffix",
            email = "invalid-role-user-$suffix@example.test",
            displayName = "Invalid Role User $suffix",
        )

        assertFailsWith<DataAccessException> {
            jdbcTemplate.update(
                "insert into practitioners (user_id, display_name, status) values (?, ?, ?)",
                user.id.value,
                "Invalid Practitioner Status",
                "enabled",
            )
        }

        assertFailsWith<DataAccessException> {
            jdbcTemplate.update(
                "insert into oauth_clients (organization_id, client_identifier, display_name, status) values (?, ?, ?, ?)",
                organization.id.value,
                "invalid-client-status-$suffix",
                "Invalid Client Status",
                "enabled",
            )
        }

        assertFailsWith<DataAccessException> {
            jdbcTemplate.update(
                "insert into memberships (organization_id, user_id, status) values (?, ?, ?)",
                organization.id.value,
                user.id.value,
                "enabled",
            )
        }

        val membership = membershipRepository.create(
            organizationId = organization.id,
            userId = user.id,
        )

        assertFailsWith<DataAccessException> {
            jdbcTemplate.update(
                "insert into membership_roles (membership_id, role) values (?, ?)",
                membership.id.value,
                "ROOT",
            )
        }
    }
}
