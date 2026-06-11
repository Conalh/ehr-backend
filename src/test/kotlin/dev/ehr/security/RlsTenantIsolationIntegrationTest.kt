package dev.ehr.security

import dev.ehr.identity.Organization
import dev.ehr.identity.OrganizationRepository
import dev.ehr.patient.Patient
import dev.ehr.patient.PatientCreateCommand
import dev.ehr.patient.PatientRepository
import dev.ehr.testsupport.PostgresIntegrationTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.UUID

/**
 * Slice H5: tenant RLS beneath the repository layer.
 *
 * Postgres superusers bypass RLS unconditionally and the test container
 * connects as one, so the policies are proven through a dedicated
 * non-superuser probe role on a raw JDBC connection, and the GUC plumbing is
 * proven through the application DataSource.
 */
class RlsTenantIsolationIntegrationTest : PostgresIntegrationTest() {
    @Autowired
    lateinit var organizationRepository: OrganizationRepository

    @Autowired
    lateinit var patientRepository: PatientRepository

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Test
    fun `rls policies hide and reject other tenants rows for context-bearing connections`() {
        val orgA = createOrganization()
        val orgB = createOrganization()
        val patientA = createPatient(orgA)
        val patientB = createPatient(orgB)
        ensureProbeRole()

        probeConnection().use { connection ->
            // Without tenant context the bypass applies: both rows visible.
            assertEquals(2, countPatients(connection, patientA, patientB))

            // With org A context, org B's row is invisible...
            setTenant(connection, orgA.id.value)
            assertEquals(1, countPatients(connection, patientA, patientB))
            assertEquals(0, countPatientById(connection, patientB))

            // ...and unwritable: an insert claiming org B fails the policy.
            val violation = assertThrows(SQLException::class.java) {
                connection.prepareStatement(
                    """
                    insert into patients (organization_id, given_name, family_name)
                    values (?, 'Cross', 'Tenant')
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, orgB.id.value)
                    statement.executeUpdate()
                }
            }
            assertEquals(true, violation.message?.contains("row-level security"))
        }
    }

    @Test
    fun `the application datasource carries the tenant guc exactly while context is bound`() {
        val organization = createOrganization()

        try {
            TenantContextHolder.set(organization.id)
            assertEquals(organization.id.value.toString(), currentGuc())
        } finally {
            TenantContextHolder.clear()
        }
        assertEquals("unset", currentGuc())
    }

    private fun currentGuc(): String =
        jdbcTemplate.queryForObject(
            "select coalesce(nullif(current_setting('ehr.organization_id', true), ''), 'unset')",
            String::class.java,
        )!!

    private fun setTenant(
        connection: Connection,
        organizationId: UUID,
    ) {
        connection.prepareStatement("select set_config('ehr.organization_id', ?, false)").use { statement ->
            statement.setString(1, organizationId.toString())
            statement.execute()
        }
    }

    private fun countPatients(
        connection: Connection,
        vararg patients: Patient,
    ): Int =
        connection.prepareStatement(
            "select count(*) from patients where id = any (?)",
        ).use { statement ->
            statement.setArray(1, connection.createArrayOf("uuid", patients.map { it.id.value }.toTypedArray()))
            statement.executeQuery().use { rs ->
                rs.next()
                rs.getInt(1)
            }
        }

    private fun countPatientById(
        connection: Connection,
        patient: Patient,
    ): Int =
        connection.prepareStatement("select count(*) from patients where id = ?").use { statement ->
            statement.setObject(1, patient.id.value)
            statement.executeQuery().use { rs ->
                rs.next()
                rs.getInt(1)
            }
        }

    private fun ensureProbeRole() {
        jdbcTemplate.execute(
            """
            do $$
            begin
                if not exists (select 1 from pg_roles where rolname = 'rls_probe') then
                    create role rls_probe login password 'rls_probe_pw';
                end if;
            end;
            $$
            """.trimIndent(),
        )
        jdbcTemplate.execute("grant usage on schema public to rls_probe")
        jdbcTemplate.execute("grant select, insert, update, delete on all tables in schema public to rls_probe")
    }

    private fun probeConnection(): Connection =
        DriverManager.getConnection(postgres.jdbcUrl, "rls_probe", "rls_probe_pw")

    private fun createOrganization(): Organization {
        val suffix = UUID.randomUUID()
        return organizationRepository.create(
            slug = "rls-org-$suffix",
            displayName = "Rls Org $suffix",
        )
    }

    private fun createPatient(organization: Organization): Patient =
        patientRepository.create(
            PatientCreateCommand(
                organizationId = organization.id,
                givenName = "Synthetic",
                familyName = "Patient",
            ),
        )
}
