package dev.ehr.medication

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

class MedicationStatementSchemaMigrationTest : PostgresIntegrationTest() {
    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Test
    fun `creates medication statements table with compartment and coded shape`() {
        val columns = jdbcTemplate.query(
            """
            select column_name, is_nullable
            from information_schema.columns
            where table_schema = 'public'
              and table_name = 'medication_statements'
            order by ordinal_position
            """.trimIndent(),
            { rs, _ -> rs.getString("column_name") to rs.getString("is_nullable") },
        ).toMap()

        assertEquals("NO", columns["organization_id"])
        assertEquals("NO", columns["patient_id"])
        assertEquals("YES", columns["encounter_id"])
        assertEquals("NO", columns["status"])
        assertEquals("NO", columns["medication_concept_id"])
        assertEquals("YES", columns["dosage_text"])
        assertEquals("YES", columns["effective_start"])
        assertEquals("YES", columns["effective_end"])
        assertEquals("NO", columns["recorded_at"])
        assertEquals("NO", columns["version"])
    }

    @Test
    fun `medication statement constraints fail at database layer`() {
        val organizationId = insertOrganization()
        val patientId = insertPatient(organizationId)
        val conceptId = insertConcept()

        assertFailsWith<DataAccessException> {
            insertStatement(organizationId, patientId, conceptId, status = "intended")
        }
        assertFailsWith<DataAccessException> {
            insertStatement(organizationId, patientId, conceptId, dosageText = "  ")
        }
        assertFailsWith<DataAccessException> {
            insertStatement(
                organizationId,
                patientId,
                conceptId,
                effectiveStart = LocalDate.of(2026, 5, 1),
                effectiveEnd = LocalDate.of(2026, 1, 1),
            )
        }
    }

    @Test
    fun `medication statements must reference patient in the same organization`() {
        val northOrganizationId = insertOrganization()
        val southOrganizationId = insertOrganization()
        val northPatientId = insertPatient(northOrganizationId)
        val conceptId = insertConcept()

        assertFailsWith<DataAccessException> {
            insertStatement(southOrganizationId, northPatientId, conceptId)
        }
    }

    @Test
    fun `medication statement indexes start with organization`() {
        val indexDefinitions = jdbcTemplate.queryForList(
            """
            select indexdef
            from pg_indexes
            where schemaname = 'public'
              and tablename = 'medication_statements'
              and indexname in (
                'medication_statements_organization_patient_recorded_idx',
                'medication_statements_organization_status_idx',
                'medication_statements_organization_medication_idx'
              )
            """.trimIndent(),
            String::class.java,
        )

        assertEquals(3, indexDefinitions.size)
        assertTrue(indexDefinitions.all { it.contains("(organization_id") })
    }

    private fun insertOrganization(): UUID =
        jdbcTemplate.queryForObject(
            "insert into organizations (slug, display_name) values (?, ?) returning id",
            UUID::class.java,
            "med-schema-org-${UUID.randomUUID()}",
            "Med Schema Org",
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

    private fun insertConcept(): UUID {
        val codingId = jdbcTemplate.queryForObject(
            """
            insert into codings (system, code, display)
            values ('http://www.nlm.nih.gov/research/umls/rxnorm', ?, 'schema test medication')
            returning id
            """.trimIndent(),
            UUID::class.java,
            "MED-SCHEMA-${UUID.randomUUID()}",
        )!!
        return jdbcTemplate.queryForObject(
            """
            insert into codeable_concepts (text, primary_coding_id)
            values ('schema test medication', ?)
            returning id
            """.trimIndent(),
            UUID::class.java,
            codingId,
        )!!
    }

    private fun insertStatement(
        organizationId: UUID,
        patientId: UUID,
        medicationConceptId: UUID,
        status: String = "active",
        dosageText: String? = null,
        effectiveStart: LocalDate? = null,
        effectiveEnd: LocalDate? = null,
    ) {
        jdbcTemplate.update(
            """
            insert into medication_statements (
              organization_id, patient_id, status, medication_concept_id,
              dosage_text, effective_start, effective_end
            )
            values (?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            organizationId,
            patientId,
            status,
            medicationConceptId,
            dosageText,
            effectiveStart?.let(Date::valueOf),
            effectiveEnd?.let(Date::valueOf),
        )
    }
}
