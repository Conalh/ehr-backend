package dev.ehr.export

import dev.ehr.identity.MembershipRepository
import dev.ehr.identity.MembershipRole
import dev.ehr.identity.Organization
import dev.ehr.identity.OrganizationRepository
import dev.ehr.identity.User
import dev.ehr.identity.UserRepository
import dev.ehr.testsupport.DevJwtFactory
import dev.ehr.testsupport.DevJwtTestConfiguration
import dev.ehr.testsupport.PostgresIntegrationTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.core.task.TaskRejectedException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import java.util.UUID

@AutoConfigureMockMvc
@Import(DevJwtTestConfiguration::class, RejectingExportDispatcherConfiguration::class)
class ExportSchedulingFailureIntegrationTest : PostgresIntegrationTest() {
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
    fun `scheduler rejection fails the job and request`() {
        val correlationId = "export-rejected-${UUID.randomUUID()}"
        val member = createMember()

        mockMvc.post("/api/v1/export-jobs") {
            header("Authorization", "Bearer ${member.token}")
            header("X-Correlation-Id", correlationId)
        }.andExpect {
            status { isServiceUnavailable() }
        }

        val job = jdbcTemplate.queryForObject(
            """
            select status, error_message
            from export_jobs
            where organization_id = ?
            """.trimIndent(),
            { rs, _ ->
                ExportJobSchedulingRow(
                    status = ExportJobStatus.fromDb(rs.getString("status")),
                    errorMessage = rs.getString("error_message"),
                )
            },
            member.organization.id.value,
        )!!
        assertEquals(ExportJobStatus.FAILED, job.status)
        assertEquals("export scheduling failed (TaskRejectedException)", job.errorMessage)

        val requestAudit = jdbcTemplate.queryForObject(
            """
            select resource_type, operation, outcome
            from audit_events
            where correlation_id = ?
            """.trimIndent(),
            { rs, _ ->
                listOf(rs.getString("resource_type"), rs.getString("operation"), rs.getString("outcome"))
            },
            correlationId,
        )!!
        assertEquals(listOf("EXPORT", "EXPORT", "FAILURE"), requestAudit)

        val backgroundFailureCount = jdbcTemplate.queryForObject(
            """
            select count(*)
            from audit_events
            where organization_id = ?
              and subject_user_id = ?
              and resource_type = 'EXPORT_JOB'
              and operation = 'SYSTEM'
              and outcome = 'FAILURE'
            """.trimIndent(),
            Int::class.java,
            member.organization.id.value,
            member.user.id.value,
        )
        assertEquals(1, backgroundFailureCount)
    }

    private fun createMember(): ExportSchedulingMemberFixture {
        val suffix = UUID.randomUUID()
        val organization = organizationRepository.create(
            slug = "export-reject-org-$suffix",
            displayName = "Export Reject Org $suffix",
        )
        val user = userRepository.create(
            externalSubject = "export-reject-user-$suffix",
            email = "export-reject-user-$suffix@example.test",
            displayName = "Export Reject User $suffix",
        )
        val membership = membershipRepository.create(
            organizationId = organization.id,
            userId = user.id,
        )
        membershipRepository.addRole(membership.id, MembershipRole.CLINICIAN)

        return ExportSchedulingMemberFixture(
            organization = organization,
            user = user,
            token = DevJwtFactory(jwtEncoder).tokenFor(
                user = user,
                organization = organization,
                scopes = "user/*.read user/*.write",
            ),
        )
    }
}

@TestConfiguration(proxyBeanMethods = false)
class RejectingExportDispatcherConfiguration {
    @Bean
    @Primary
    fun rejectingExportJobDispatcher(): ExportJobDispatcher =
        ExportJobDispatcher {
            throw TaskRejectedException("export queue full")
        }
}

data class ExportSchedulingMemberFixture(
    val organization: Organization,
    val user: User,
    val token: String,
)

data class ExportJobSchedulingRow(
    val status: ExportJobStatus,
    val errorMessage: String?,
)
