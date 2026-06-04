package dev.ehr.security

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID
import kotlin.test.assertFailsWith

@SpringBootTest
@Testcontainers
class SecurityAuditSchemaMigrationTest {
    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Test
    fun `creates identity and audit schema tables`() {
        val tables = jdbcTemplate.queryForList(
            """
            select table_name
            from information_schema.tables
            where table_schema = 'public'
              and table_name in (
                'organizations',
                'users',
                'practitioners',
                'memberships',
                'membership_roles',
                'oauth_clients',
                'audit_events',
                'audit_event_resources'
              )
            order by table_name
            """.trimIndent(),
            String::class.java,
        )

        assertEquals(
            listOf(
                "audit_event_resources",
                "audit_events",
                "membership_roles",
                "memberships",
                "oauth_clients",
                "organizations",
                "practitioners",
                "users",
            ),
            tables,
        )
    }

    @Test
    fun `enforces membership role values`() {
        val organizationId = insertOrganization("north-clinic", "North Clinic")
        val userId = insertUser("clinician-1", "clinician@example.test", "Clinician One")
        val membershipId = insertMembership(organizationId, userId)

        jdbcTemplate.update(
            "insert into membership_roles (membership_id, role) values (?, ?)",
            membershipId,
            "CLINICIAN",
        )

        val roles = jdbcTemplate.queryForList(
            "select role from membership_roles where membership_id = ?",
            String::class.java,
            membershipId,
        )
        assertEquals(listOf("CLINICIAN"), roles)

        assertFailsWith<DataAccessException> {
            jdbcTemplate.update(
                "insert into membership_roles (membership_id, role) values (?, ?)",
                membershipId,
                "UNSCOPED_SUPERUSER",
            )
        }
    }

    @Test
    fun `keeps audit events append only`() {
        val organizationId = insertOrganization("audit-clinic", "Audit Clinic")
        val userId = insertUser("auditor-1", "auditor@example.test", "Audit User")
        val eventId = jdbcTemplate.queryForObject(
            """
            insert into audit_events (
              organization_id,
              subject_user_id,
              resource_type,
              operation,
              outcome,
              correlation_id
            )
            values (?, ?, 'Organization', 'READ', 'SUCCESS', 'corr-1')
            returning id
            """.trimIndent(),
            UUID::class.java,
            organizationId,
            userId,
        )

        assertTrue(eventId != null)

        assertFailsWith<DataAccessException> {
            jdbcTemplate.update(
                "update audit_events set outcome = 'FAILURE' where id = ?",
                eventId,
            )
        }

        assertFailsWith<DataAccessException> {
            jdbcTemplate.update("delete from audit_events where id = ?", eventId)
        }
    }

    private fun insertOrganization(slug: String, displayName: String): UUID =
        jdbcTemplate.queryForObject(
            "insert into organizations (slug, display_name) values (?, ?) returning id",
            UUID::class.java,
            slug,
            displayName,
        )!!

    private fun insertUser(externalSubject: String, email: String, displayName: String): UUID =
        jdbcTemplate.queryForObject(
            "insert into users (external_subject, email, display_name) values (?, ?, ?) returning id",
            UUID::class.java,
            externalSubject,
            email,
            displayName,
        )!!

    private fun insertMembership(organizationId: UUID, userId: UUID): UUID =
        jdbcTemplate.queryForObject(
            "insert into memberships (organization_id, user_id) values (?, ?) returning id",
            UUID::class.java,
            organizationId,
            userId,
        )!!

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("ehr_core_test")
            .withUsername("ehr_core")
            .withPassword("ehr_core_dev")
    }
}
