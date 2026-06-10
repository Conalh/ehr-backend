package dev.ehr.runtime

import dev.ehr.identity.MembershipRepository
import dev.ehr.identity.MembershipRole
import dev.ehr.identity.OrganizationRepository
import dev.ehr.identity.UserRepository
import dev.ehr.testsupport.DevJwtFactory
import dev.ehr.testsupport.DevJwtTestConfiguration
import dev.ehr.testsupport.PostgresIntegrationTest
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import java.util.UUID

@AutoConfigureMockMvc
@Import(DevJwtTestConfiguration::class)
class HardeningIntegrationTest : PostgresIntegrationTest() {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var organizationRepository: OrganizationRepository

    @Autowired
    lateinit var userRepository: UserRepository

    @Autowired
    lateinit var membershipRepository: MembershipRepository

    @Autowired
    lateinit var jwtEncoder: JwtEncoder

    @Test
    fun `responses carry hardening headers`() {
        mockMvc.get("/fhir/r4/metadata")
            .andExpect {
                status { isOk() }
                header { string("Content-Security-Policy", "default-src 'none'") }
                header { string("Referrer-Policy", "no-referrer") }
                header { string("X-Content-Type-Options", "nosniff") }
            }
    }

    @Test
    fun `metrics require authentication while health stays public`() {
        mockMvc.get("/actuator/health")
            .andExpect {
                status { isOk() }
            }

        mockMvc.get("/actuator/metrics")
            .andExpect {
                status { isUnauthorized() }
            }

        val token = authenticatedToken()
        mockMvc.get("/actuator/metrics") {
            header("Authorization", "Bearer $token")
        }.andExpect {
            status { isOk() }
        }
    }

    @Test
    fun `unknown routes are denied rather than silently permitted`() {
        mockMvc.get("/definitely-not-a-route")
            .andExpect {
                status { isUnauthorized() }
            }
    }

    private fun authenticatedToken(): String {
        val suffix = UUID.randomUUID()
        val organization = organizationRepository.create(
            slug = "hardening-org-$suffix",
            displayName = "Hardening Org $suffix",
        )
        val user = userRepository.create(
            externalSubject = "hardening-user-$suffix",
            email = "hardening-user-$suffix@example.test",
            displayName = "Hardening User $suffix",
        )
        val membership = membershipRepository.create(
            organizationId = organization.id,
            userId = user.id,
        )
        membershipRepository.addRole(membership.id, MembershipRole.CLINICIAN)

        return DevJwtFactory(jwtEncoder).tokenFor(
            user = user,
            organization = organization,
            scopes = "user/*.read",
        )
    }
}
