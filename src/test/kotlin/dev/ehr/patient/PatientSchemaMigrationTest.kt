package dev.ehr.patient

import dev.ehr.testsupport.PostgresIntegrationTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.Date
import java.time.LocalDate
import java.util.UUID
import kotlin.test.assertFailsWith

class PatientSchemaMigrationTest : PostgresIntegrationTest() {
    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Test
    fun `creates patient registry tables`() {
        val tables = jdbcTemplate.queryForList(
            """
            select table_name
            from information_schema.tables
            where table_schema = 'public'
              and table_name in ('patients', 'patient_identifiers')
            order by table_name
            """.trimIndent(),
            String::class.java,
        )

        assertEquals(listOf("patient_identifiers", "patients"), tables)
    }

    @Test
    fun `patient registry columns preserve tenant and lifecycle shape`() {
        val columns = jdbcTemplate.query(
            """
            select table_name, column_name, is_nullable
            from information_schema.columns
            where table_schema = 'public'
              and (
                table_name = 'patients'
                or table_name = 'patient_identifiers'
              )
            order by table_name, ordinal_position
            """.trimIndent(),
            { rs, _ ->
                "${rs.getString("table_name")}.${rs.getString("column_name")}" to rs.getString("is_nullable")
            },
        ).toMap()

        assertEquals("NO", columns["patients.organization_id"])
        assertEquals("NO", columns["patients.status"])
        assertEquals("NO", columns["patients.given_name"])
        assertEquals("NO", columns["patients.family_name"])
        assertEquals("NO", columns["patients.version"])
        assertEquals("NO", columns["patient_identifiers.organization_id"])
        assertEquals("NO", columns["patient_identifiers.patient_id"])
        assertEquals("NO", columns["patient_identifiers.system"])
        assertEquals("NO", columns["patient_identifiers.value"])
    }

    @Test
    fun `patient registry indexes start with organization for tenant scoped access`() {
        val indexDefinitions = jdbcTemplate.queryForList(
            """
            select indexdef
            from pg_indexes
            where schemaname = 'public'
              and tablename in ('patients', 'patient_identifiers')
              and indexname in (
                'patients_organization_status_idx',
                'patients_organization_family_given_idx',
                'patient_identifiers_organization_patient_idx',
                'patient_identifiers_organization_system_value_key'
              )
            order by indexname
            """.trimIndent(),
            String::class.java,
        )

        assertEquals(4, indexDefinitions.size)
        assertTrue(indexDefinitions.all { it.contains("(organization_id") })
    }

    @Test
    fun `patient lifecycle and demographic constraints fail at database layer`() {
        val organizationId = insertOrganization("patient-schema-org", "Patient Schema Org")

        assertFailsWith<DataAccessException> {
            jdbcTemplate.update(
                """
                insert into patients (organization_id, status, given_name, family_name)
                values (?, ?, ?, ?)
                """.trimIndent(),
                organizationId,
                "draft",
                "Synthetic",
                "Patient",
            )
        }
        assertFailsWith<DataAccessException> {
            jdbcTemplate.update(
                """
                insert into patients (organization_id, given_name, family_name, administrative_gender)
                values (?, ?, ?, ?)
                """.trimIndent(),
                organizationId,
                "Synthetic",
                "Patient",
                "ambiguous",
            )
        }
        assertFailsWith<DataAccessException> {
            jdbcTemplate.update(
                """
                insert into patients (organization_id, given_name, family_name)
                values (?, ?, ?)
                """.trimIndent(),
                organizationId,
                " ",
                "Patient",
            )
        }
        assertFailsWith<DataAccessException> {
            jdbcTemplate.update(
                """
                insert into patients (organization_id, given_name, family_name)
                values (?, ?, ?)
                """.trimIndent(),
                organizationId,
                "Synthetic",
                " ",
            )
        }
    }

    @Test
    fun `patient identifier constraints fail at database layer`() {
        val organizationId = insertOrganization("identifier-schema-org", "Identifier Schema Org")
        val patientId = insertPatient(organizationId)

        assertFailsWith<DataAccessException> {
            insertIdentifier(organizationId, patientId, system = "https://example.test/mrn", value = "MRN-1", use = "primary")
        }
        assertFailsWith<DataAccessException> {
            insertIdentifier(organizationId, patientId, system = " ", value = "MRN-1")
        }
        assertFailsWith<DataAccessException> {
            insertIdentifier(organizationId, patientId, system = "https://example.test/mrn", value = " ")
        }
        assertFailsWith<DataAccessException> {
            insertIdentifier(
                organizationId,
                patientId,
                system = "https://example.test/mrn",
                value = "MRN-1",
                assignerText = " ",
            )
        }
        assertFailsWith<DataAccessException> {
            insertIdentifier(
                organizationId,
                patientId,
                system = "https://example.test/mrn",
                value = "MRN-1",
                periodStart = LocalDate.of(2026, 2, 1),
                periodEnd = LocalDate.of(2026, 1, 1),
            )
        }
    }

    @Test
    fun `patient identifiers must belong to the same organization as the patient`() {
        val northOrganizationId = insertOrganization("same-org-north", "Same Org North")
        val southOrganizationId = insertOrganization("same-org-south", "Same Org South")
        val northPatientId = insertPatient(northOrganizationId)

        assertFailsWith<DataAccessException> {
            insertIdentifier(
                organizationId = southOrganizationId,
                patientId = northPatientId,
                system = "https://example.test/mrn",
                value = "MRN-ORG-MISMATCH",
            )
        }
    }

    @Test
    fun `patient identifiers are unique per organization system and value`() {
        val organizationId = insertOrganization("identifier-unique-org", "Identifier Unique Org")
        val patientId = insertPatient(organizationId)
        insertIdentifier(
            organizationId = organizationId,
            patientId = patientId,
            system = "https://example.test/mrn",
            value = "MRN-UNIQUE",
        )

        assertFailsWith<DataAccessException> {
            insertIdentifier(
                organizationId = organizationId,
                patientId = patientId,
                system = "https://example.test/mrn",
                value = "MRN-UNIQUE",
            )
        }
    }

    private fun insertOrganization(slugPrefix: String, displayName: String): UUID =
        jdbcTemplate.queryForObject(
            "insert into organizations (slug, display_name) values (?, ?) returning id",
            UUID::class.java,
            "$slugPrefix-${UUID.randomUUID()}",
            displayName,
        )!!

    private fun insertPatient(organizationId: UUID): UUID =
        jdbcTemplate.queryForObject(
            """
            insert into patients (organization_id, given_name, family_name)
            values (?, ?, ?)
            returning id
            """.trimIndent(),
            UUID::class.java,
            organizationId,
            "Synthetic",
            "Patient",
        )!!

    private fun insertIdentifier(
        organizationId: UUID,
        patientId: UUID,
        system: String,
        value: String,
        use: String? = null,
        assignerText: String? = null,
        periodStart: LocalDate? = null,
        periodEnd: LocalDate? = null,
    ) {
        jdbcTemplate.update(
            """
            insert into patient_identifiers (
              organization_id,
              patient_id,
              system,
              value,
              use,
              assigner_text,
              period_start,
              period_end
            )
            values (?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            organizationId,
            patientId,
            system,
            value,
            use,
            assignerText,
            periodStart?.let(Date::valueOf),
            periodEnd?.let(Date::valueOf),
        )
    }
}
