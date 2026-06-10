package dev.ehr.security

import dev.ehr.identity.MembershipRepository
import dev.ehr.identity.MembershipRole
import dev.ehr.identity.MembershipStatus
import dev.ehr.identity.OAuthClientId
import dev.ehr.identity.OrganizationRepository
import dev.ehr.identity.OrganizationStatus
import dev.ehr.identity.UserRepository
import dev.ehr.identity.UserStatus
import dev.ehr.testsupport.DevJwtFactory
import dev.ehr.testsupport.DevJwtTestConfiguration
import dev.ehr.testsupport.PostgresIntegrationTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import java.util.UUID

@AutoConfigureMockMvc
@Import(DevJwtTestConfiguration::class)
class DevJwtAuthenticationIntegrationTest : PostgresIntegrationTest() {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var organizationRepository: OrganizationRepository

    @Autowired
    lateinit var userRepository: UserRepository

    @Autowired
    lateinit var membershipRepository: MembershipRepository

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    lateinit var jwtEncoder: JwtEncoder

    @Test
    fun `health endpoint remains public`() {
        mockMvc.get("/internal/health")
            .andExpect {
                status { isOk() }
                jsonPath("$.status") { value("UP") }
            }
    }

    @Test
    fun `protected placeholder endpoint rejects unauthenticated requests`() {
        mockMvc.get("/api/v1/security/whoami")
            .andExpect {
                status { isUnauthorized() }
            }
    }

    @Test
    fun `valid dev JWT authenticates and exposes principal context`() {
        val suffix = UUID.randomUUID()
        val organization = organizationRepository.create(
            slug = "auth-org-$suffix",
            displayName = "Auth Org $suffix",
        )
        val user = userRepository.create(
            externalSubject = "auth-user-$suffix",
            email = "auth-user-$suffix@example.test",
            displayName = "Auth User $suffix",
        )
        val membership = membershipRepository.create(
            organizationId = organization.id,
            userId = user.id,
        )
        membershipRepository.addRole(membership.id, MembershipRole.CLINICIAN)
        membershipRepository.addRole(membership.id, MembershipRole.ORG_ADMIN)
        val clientId = OAuthClientId(UUID.randomUUID())
        val token = DevJwtFactory(jwtEncoder).tokenFor(
            user = user,
            organization = organization,
            scopes = "user/*.read patient/Patient.rs system/*.read",
            clientId = clientId,
        )

        mockMvc.get("/api/v1/security/whoami") {
            header("Authorization", "Bearer $token")
        }.andExpect {
            status { isOk() }
            jsonPath("$.externalSubject") { value(user.externalSubject) }
            jsonPath("$.userId") { value(user.id.value.toString()) }
            jsonPath("$.organizationId") { value(organization.id.value.toString()) }
            jsonPath("$.membershipId") { value(membership.id.value.toString()) }
            jsonPath("$.roles[0]") { value("CLINICIAN") }
            jsonPath("$.roles[1]") { value("ORG_ADMIN") }
            jsonPath("$.scopes[0]") { value("user/*.read") }
            jsonPath("$.scopes[1]") { value("patient/Patient.rs") }
            jsonPath("$.scopes[2]") { value("system/*.read") }
            jsonPath("$.clientId") { value(clientId.value.toString()) }
        }

        assertEquals(0, auditEventCount())
    }

    @Test
    fun `valid dev JWT can resolve organization by slug`() {
        val suffix = UUID.randomUUID()
        val organization = organizationRepository.create(
            slug = "slug-auth-org-$suffix",
            displayName = "Slug Auth Org $suffix",
        )
        val user = userRepository.create(
            externalSubject = "slug-auth-user-$suffix",
            email = "slug-auth-user-$suffix@example.test",
            displayName = "Slug Auth User $suffix",
        )
        val membership = membershipRepository.create(
            organizationId = organization.id,
            userId = user.id,
        )
        membershipRepository.addRole(membership.id, MembershipRole.STAFF)
        val token = DevJwtFactory(jwtEncoder).token(
            subject = user.externalSubject,
            organizationSlug = organization.slug,
            scopes = "user/*.read",
        )

        mockMvc.get("/api/v1/security/whoami") {
            header("Authorization", "Bearer $token")
        }.andExpect {
            status { isOk() }
            jsonPath("$.organizationId") { value(organization.id.value.toString()) }
            jsonPath("$.membershipId") { value(membership.id.value.toString()) }
            jsonPath("$.roles[0]") { value("STAFF") }
        }
    }

    @Test
    fun `unknown user subject is rejected`() {
        val suffix = UUID.randomUUID()
        val organization = organizationRepository.create(
            slug = "unknown-user-org-$suffix",
            displayName = "Unknown User Org $suffix",
        )
        val token = DevJwtFactory(jwtEncoder).token(
            subject = "missing-user-$suffix",
            organizationId = organization.id.value.toString(),
        )

        mockMvc.get("/api/v1/security/whoami") {
            header("Authorization", "Bearer $token")
        }.andExpect {
            status { isUnauthorized() }
        }

        assertEquals(0, auditEventCount())
    }

    @Test
    fun `unknown organization is rejected`() {
        val suffix = UUID.randomUUID()
        val user = userRepository.create(
            externalSubject = "unknown-org-user-$suffix",
            email = "unknown-org-user-$suffix@example.test",
            displayName = "Unknown Org User $suffix",
        )
        val token = DevJwtFactory(jwtEncoder).token(
            subject = user.externalSubject,
            organizationId = UUID.randomUUID().toString(),
        )

        mockMvc.get("/api/v1/security/whoami") {
            header("Authorization", "Bearer $token")
        }.andExpect {
            status { isUnauthorized() }
        }

        assertEquals(0, auditEventCount())
    }

    @Test
    fun `user cannot authenticate into organization where they have no membership`() {
        val suffix = UUID.randomUUID()
        val north = organizationRepository.create(
            slug = "membership-north-$suffix",
            displayName = "Membership North $suffix",
        )
        val south = organizationRepository.create(
            slug = "membership-south-$suffix",
            displayName = "Membership South $suffix",
        )
        val user = userRepository.create(
            externalSubject = "membership-user-$suffix",
            email = "membership-user-$suffix@example.test",
            displayName = "Membership User $suffix",
        )
        val membership = membershipRepository.create(
            organizationId = north.id,
            userId = user.id,
        )
        membershipRepository.addRole(membership.id, MembershipRole.CLINICIAN)
        val token = DevJwtFactory(jwtEncoder).tokenFor(
            user = user,
            organization = south,
        )

        mockMvc.get("/api/v1/security/whoami") {
            header("Authorization", "Bearer $token")
        }.andExpect {
            status { isUnauthorized() }
        }

        assertEquals(0, auditEventCount())
    }

    @Test
    fun `inactive membership is rejected`() {
        assertMembershipStatusIsRejected(MembershipStatus.INACTIVE)
    }

    @Test
    fun `suspended membership is rejected`() {
        assertMembershipStatusIsRejected(MembershipStatus.SUSPENDED)
    }

    @Test
    fun `inactive user is rejected`() {
        assertUserStatusIsRejected(UserStatus.INACTIVE)
    }

    @Test
    fun `locked user is rejected`() {
        assertUserStatusIsRejected(UserStatus.LOCKED)
    }

    @Test
    fun `suspended organization is rejected`() {
        assertOrganizationStatusIsRejected(OrganizationStatus.SUSPENDED)
    }

    @Test
    fun `inactive organization is rejected`() {
        assertOrganizationStatusIsRejected(OrganizationStatus.INACTIVE)
    }

    @Test
    fun `fhir path requires authentication by default`() {
        // The CapabilityStatement is public by FHIR convention; clinical routes are not.
        mockMvc.get("/fhir/r4/metadata")
            .andExpect {
                status { isOk() }
            }
        mockMvc.get("/fhir/r4/Patient/${UUID.randomUUID()}")
            .andExpect {
                status { isUnauthorized() }
            }
    }

    private fun assertMembershipStatusIsRejected(status: MembershipStatus) {
        val suffix = UUID.randomUUID()
        val organization = organizationRepository.create(
            slug = "membership-status-${status.dbValue}-$suffix",
            displayName = "Membership Status $status $suffix",
        )
        val user = userRepository.create(
            externalSubject = "membership-status-${status.dbValue}-$suffix",
            email = "membership-status-${status.dbValue}-$suffix@example.test",
            displayName = "Membership Status User $suffix",
        )
        val membership = membershipRepository.create(
            organizationId = organization.id,
            userId = user.id,
            status = status,
        )
        membershipRepository.addRole(membership.id, MembershipRole.CLINICIAN)
        val token = DevJwtFactory(jwtEncoder).tokenFor(
            user = user,
            organization = organization,
        )

        mockMvc.get("/api/v1/security/whoami") {
            header("Authorization", "Bearer $token")
        }.andExpect {
            status { isUnauthorized() }
        }

        assertEquals(0, auditEventCount())
    }

    private fun assertUserStatusIsRejected(status: UserStatus) {
        val suffix = UUID.randomUUID()
        val organization = organizationRepository.create(
            slug = "user-status-${status.dbValue}-$suffix",
            displayName = "User Status $status $suffix",
        )
        val user = userRepository.create(
            externalSubject = "user-status-${status.dbValue}-$suffix",
            email = "user-status-${status.dbValue}-$suffix@example.test",
            displayName = "User Status User $suffix",
            status = status,
        )
        val membership = membershipRepository.create(
            organizationId = organization.id,
            userId = user.id,
        )
        membershipRepository.addRole(membership.id, MembershipRole.CLINICIAN)
        val token = DevJwtFactory(jwtEncoder).tokenFor(
            user = user,
            organization = organization,
        )

        mockMvc.get("/api/v1/security/whoami") {
            header("Authorization", "Bearer $token")
        }.andExpect {
            status { isUnauthorized() }
        }

        assertEquals(0, auditEventCount())
    }

    private fun assertOrganizationStatusIsRejected(status: OrganizationStatus) {
        val suffix = UUID.randomUUID()
        val organization = organizationRepository.create(
            slug = "org-status-${status.dbValue}-$suffix",
            displayName = "Org Status $status $suffix",
            status = status,
        )
        val user = userRepository.create(
            externalSubject = "org-status-${status.dbValue}-$suffix",
            email = "org-status-${status.dbValue}-$suffix@example.test",
            displayName = "Org Status User $suffix",
        )
        val membership = membershipRepository.create(
            organizationId = organization.id,
            userId = user.id,
        )
        membershipRepository.addRole(membership.id, MembershipRole.CLINICIAN)
        val token = DevJwtFactory(jwtEncoder).tokenFor(
            user = user,
            organization = organization,
        )

        mockMvc.get("/api/v1/security/whoami") {
            header("Authorization", "Bearer $token")
        }.andExpect {
            status { isUnauthorized() }
        }

        assertEquals(0, auditEventCount())
    }

    private fun auditEventCount(): Int =
        jdbcTemplate.queryForObject("select count(*) from audit_events", Int::class.java)!!
}
