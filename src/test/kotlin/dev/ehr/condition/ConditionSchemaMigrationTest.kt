package dev.ehr.condition

import dev.ehr.testsupport.PostgresIntegrationTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.Date
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.test.assertFailsWith

class ConditionSchemaMigrationTest : PostgresIntegrationTest() {
    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Test
    fun `creates conditions table with compartment and coded shape`() {
        val columns = jdbcTemplate.query(
            """
            select column_name, is_nullable
            from information_schema.columns
            where table_schema = 'public'
              and table_name = 'conditions'
            order by ordinal_position
            """.trimIndent(),
            { rs, _ -> rs.getString("column_name") to rs.getString("is_nullable") },
        ).toMap()

        assertEquals("NO", columns["organization_id"])
        assertEquals("NO", columns["patient_id"])
        assertEquals("YES", columns["encounter_id"])
        assertEquals("NO", columns["clinical_status"])
        assertEquals("NO", columns["verification_status"])
        assertEquals("NO", columns["code_concept_id"])
        assertEquals("YES", columns["onset_date"])
        assertEquals("YES", columns["abatement_date"])
        assertEquals("NO", columns["recorded_at"])
        assertEquals("NO", columns["version"])
    }

    @Test
    fun `condition indexes start with organization for tenant scoped access`() {
        val indexDefinitions = jdbcTemplate.queryForList(
            """
            select indexdef
            from pg_indexes
            where schemaname = 'public'
              and tablename = 'conditions'
              and indexname in (
                'conditions_organization_patient_recorded_idx',
                'conditions_organization_clinical_status_idx',
                'conditions_organization_encounter_idx',
                'conditions_organization_code_idx'
              )
            """.trimIndent(),
            String::class.java,
        )

        assertEquals(4, indexDefinitions.size)
        assertTrue(indexDefinitions.all { it.contains("(organization_id") })
    }

    @Test
    fun `condition constraints fail at database layer`() {
        val organizationId = insertOrganization()
        val patientId = insertPatient(organizationId)
        val conceptId = insertConcept()

        // invalid clinical status
        assertFailsWith<DataAccessException> {
            insertCondition(organizationId, patientId, conceptId, clinicalStatus = "chronic")
        }
        // invalid verification status
        assertFailsWith<DataAccessException> {
            insertCondition(organizationId, patientId, conceptId, verificationStatus = "suspected")
        }
        // abatement before onset
        assertFailsWith<DataAccessException> {
            insertCondition(
                organizationId,
                patientId,
                conceptId,
                onsetDate = LocalDate.of(2026, 5, 1),
                abatementDate = LocalDate.of(2026, 1, 1),
            )
        }
        // version must be positive
        assertFailsWith<DataAccessException> {
            jdbcTemplate.update(
                """
                insert into conditions (organization_id, patient_id, code_concept_id, version)
                values (?, ?, ?, 0)
                """.trimIndent(),
                organizationId,
                patientId,
                conceptId,
            )
        }
    }

    @Test
    fun `conditions must reference patient and encounter in the same organization`() {
        val northOrganizationId = insertOrganization()
        val southOrganizationId = insertOrganization()
        val northPatientId = insertPatient(northOrganizationId)
        val southPatientId = insertPatient(southOrganizationId)
        val conceptId = insertConcept()
        val northEncounterId = insertEncounter(northOrganizationId, northPatientId, conceptId)

        // cross-org patient
        assertFailsWith<DataAccessException> {
            insertCondition(southOrganizationId, northPatientId, conceptId)
        }
        // cross-org encounter link
        assertFailsWith<DataAccessException> {
            insertCondition(southOrganizationId, southPatientId, conceptId, encounterId = northEncounterId)
        }
    }

    @Test
    fun `conditions expose a composite organization id key for future references`() {
        val constraints = jdbcTemplate.queryForList(
            """
            select constraint_name
            from information_schema.table_constraints
            where table_schema = 'public'
              and table_name = 'conditions'
              and constraint_type = 'UNIQUE'
            """.trimIndent(),
            String::class.java,
        )

        assertTrue("conditions_organization_id_id_key" in constraints)
    }

    private fun insertOrganization(): UUID =
        jdbcTemplate.queryForObject(
            "insert into organizations (slug, display_name) values (?, ?) returning id",
            UUID::class.java,
            "condition-schema-org-${UUID.randomUUID()}",
            "Condition Schema Org",
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
            values ('http://snomed.info/sct', ?, 'schema test concept')
            returning id
            """.trimIndent(),
            UUID::class.java,
            "COND-SCHEMA-${UUID.randomUUID()}",
        )!!
        return jdbcTemplate.queryForObject(
            """
            insert into codeable_concepts (text, primary_coding_id)
            values ('schema test concept', ?)
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
    ): UUID =
        jdbcTemplate.queryForObject(
            """
            insert into encounters (organization_id, patient_id, status, class_concept_id, period_start)
            values (?, ?, 'planned', ?, ?)
            returning id
            """.trimIndent(),
            UUID::class.java,
            organizationId,
            patientId,
            classConceptId,
            Timestamp.from(Instant.parse("2026-06-01T09:00:00Z")),
        )!!

    private fun insertCondition(
        organizationId: UUID,
        patientId: UUID,
        codeConceptId: UUID,
        encounterId: UUID? = null,
        clinicalStatus: String = "active",
        verificationStatus: String = "confirmed",
        onsetDate: LocalDate? = null,
        abatementDate: LocalDate? = null,
    ) {
        jdbcTemplate.update(
            """
            insert into conditions (
              organization_id, patient_id, encounter_id, clinical_status,
              verification_status, code_concept_id, onset_date, abatement_date
            )
            values (?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            organizationId,
            patientId,
            encounterId,
            clinicalStatus,
            verificationStatus,
            codeConceptId,
            onsetDate?.let(Date::valueOf),
            abatementDate?.let(Date::valueOf),
        )
    }
}
