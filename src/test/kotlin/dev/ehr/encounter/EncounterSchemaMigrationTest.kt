package dev.ehr.encounter

import dev.ehr.testsupport.PostgresIntegrationTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import kotlin.test.assertFailsWith

class EncounterSchemaMigrationTest : PostgresIntegrationTest() {
    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Test
    fun `creates encounters table with tenant and lifecycle shape`() {
        val columns = jdbcTemplate.query(
            """
            select column_name, is_nullable
            from information_schema.columns
            where table_schema = 'public'
              and table_name = 'encounters'
            order by ordinal_position
            """.trimIndent(),
            { rs, _ -> rs.getString("column_name") to rs.getString("is_nullable") },
        ).toMap()

        assertEquals("NO", columns["organization_id"])
        assertEquals("NO", columns["patient_id"])
        assertEquals("NO", columns["status"])
        assertEquals("NO", columns["class_concept_id"])
        assertEquals("NO", columns["period_start"])
        assertEquals("YES", columns["period_end"])
        assertEquals("NO", columns["version"])
        assertEquals("NO", columns["created_at"])
        assertEquals("NO", columns["updated_at"])
        assertEquals("YES", columns["created_by"])
        assertEquals("YES", columns["updated_by"])
    }

    @Test
    fun `encounter indexes start with organization for tenant scoped access`() {
        val indexDefinitions = jdbcTemplate.queryForList(
            """
            select indexdef
            from pg_indexes
            where schemaname = 'public'
              and tablename = 'encounters'
              and indexname in (
                'encounters_organization_patient_period_idx',
                'encounters_organization_status_idx',
                'encounters_organization_class_idx'
              )
            order by indexname
            """.trimIndent(),
            String::class.java,
        )

        assertEquals(3, indexDefinitions.size)
        assertTrue(indexDefinitions.all { it.contains("(organization_id") })
    }

    @Test
    fun `encounter lifecycle constraints fail at database layer`() {
        val organizationId = insertOrganization()
        val patientId = insertPatient(organizationId)
        val classConceptId = insertClassConcept()
        val start = Instant.parse("2026-06-01T09:00:00Z")

        // invalid status
        assertFailsWith<DataAccessException> {
            insertEncounter(organizationId, patientId, classConceptId, status = "arrived", periodStart = start)
        }
        // inverted period
        assertFailsWith<DataAccessException> {
            insertEncounter(
                organizationId,
                patientId,
                classConceptId,
                status = "in-progress",
                periodStart = start,
                periodEnd = start.minusSeconds(3600),
            )
        }
        // finished requires period end
        assertFailsWith<DataAccessException> {
            insertEncounter(organizationId, patientId, classConceptId, status = "finished", periodStart = start)
        }
        // version must be positive
        assertFailsWith<DataAccessException> {
            jdbcTemplate.update(
                """
                insert into encounters (organization_id, patient_id, status, class_concept_id, period_start, version)
                values (?, ?, ?, ?, ?, 0)
                """.trimIndent(),
                organizationId,
                patientId,
                "planned",
                classConceptId,
                Timestamp.from(start),
            )
        }
    }

    @Test
    fun `encounters must reference a patient in the same organization`() {
        val northOrganizationId = insertOrganization()
        val southOrganizationId = insertOrganization()
        val northPatientId = insertPatient(northOrganizationId)
        val classConceptId = insertClassConcept()

        assertFailsWith<DataAccessException> {
            insertEncounter(
                organizationId = southOrganizationId,
                patientId = northPatientId,
                classConceptId = classConceptId,
                status = "planned",
                periodStart = Instant.parse("2026-06-01T09:00:00Z"),
            )
        }
    }

    @Test
    fun `encounters expose a composite organization id key for future clinical references`() {
        val constraints = jdbcTemplate.queryForList(
            """
            select constraint_name
            from information_schema.table_constraints
            where table_schema = 'public'
              and table_name = 'encounters'
              and constraint_type = 'UNIQUE'
            """.trimIndent(),
            String::class.java,
        )

        assertTrue("encounters_organization_id_id_key" in constraints)
    }

    private fun insertOrganization(): UUID =
        jdbcTemplate.queryForObject(
            "insert into organizations (slug, display_name) values (?, ?) returning id",
            UUID::class.java,
            "encounter-schema-org-${UUID.randomUUID()}",
            "Encounter Schema Org",
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

    private fun insertClassConcept(): UUID {
        // Codings are globally unique per (system, code, version); a random schema-test
        // code avoids collisions with other tests sharing the container.
        val codingId = jdbcTemplate.queryForObject(
            """
            insert into codings (system, code, display)
            values ('http://terminology.hl7.org/CodeSystem/v3-ActCode', ?, 'ambulatory')
            returning id
            """.trimIndent(),
            UUID::class.java,
            "AMB-SCHEMA-${UUID.randomUUID()}",
        )!!
        return jdbcTemplate.queryForObject(
            """
            insert into codeable_concepts (text, primary_coding_id)
            values ('ambulatory', ?)
            returning id
            """.trimIndent(),
            UUID::class.java,
            codingId,
        )!!
    }

    private fun insertEncounter(
        organizationId: UUID,
        patientId: UUID,
        classConceptId: UUID,
        status: String,
        periodStart: Instant,
        periodEnd: Instant? = null,
    ) {
        jdbcTemplate.update(
            """
            insert into encounters (organization_id, patient_id, status, class_concept_id, period_start, period_end)
            values (?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            organizationId,
            patientId,
            status,
            classConceptId,
            Timestamp.from(periodStart),
            periodEnd?.let(Timestamp::from),
        )
    }
}
