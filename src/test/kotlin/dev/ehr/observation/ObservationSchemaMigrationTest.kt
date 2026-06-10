package dev.ehr.observation

import dev.ehr.testsupport.PostgresIntegrationTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import java.math.BigDecimal
import java.util.UUID
import kotlin.test.assertFailsWith

class ObservationSchemaMigrationTest : PostgresIntegrationTest() {
    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Test
    fun `creates observations table with compartment value and category shape`() {
        val columns = jdbcTemplate.query(
            """
            select column_name, is_nullable
            from information_schema.columns
            where table_schema = 'public'
              and table_name = 'observations'
            order by ordinal_position
            """.trimIndent(),
            { rs, _ -> rs.getString("column_name") to rs.getString("is_nullable") },
        ).toMap()

        assertEquals("NO", columns["organization_id"])
        assertEquals("NO", columns["patient_id"])
        assertEquals("YES", columns["encounter_id"])
        assertEquals("NO", columns["status"])
        assertEquals("NO", columns["category"])
        assertEquals("NO", columns["code_concept_id"])
        assertEquals("YES", columns["value_quantity"])
        assertEquals("YES", columns["value_quantity_unit"])
        assertEquals("YES", columns["value_concept_id"])
        assertEquals("YES", columns["value_text"])
        assertEquals("NO", columns["effective_at"])
        assertEquals("NO", columns["version"])
    }

    @Test
    fun `observation value constraints enforce exactly one value with paired unit`() {
        val organizationId = insertOrganization()
        val patientId = insertPatient(organizationId)
        val conceptId = insertConcept()

        // no value at all
        assertFailsWith<DataAccessException> {
            insertObservation(organizationId, patientId, conceptId)
        }
        // two values at once
        assertFailsWith<DataAccessException> {
            insertObservation(
                organizationId,
                patientId,
                conceptId,
                valueQuantity = BigDecimal("120"),
                valueQuantityUnit = "mm[Hg]",
                valueText = "high",
            )
        }
        // quantity without unit
        assertFailsWith<DataAccessException> {
            insertObservation(organizationId, patientId, conceptId, valueQuantity = BigDecimal("120"))
        }
        // unit without quantity
        assertFailsWith<DataAccessException> {
            insertObservation(
                organizationId,
                patientId,
                conceptId,
                valueQuantityUnit = "mm[Hg]",
                valueText = "high",
            )
        }
        // blank text value
        assertFailsWith<DataAccessException> {
            insertObservation(organizationId, patientId, conceptId, valueText = "  ")
        }
        // invalid category
        assertFailsWith<DataAccessException> {
            insertObservation(
                organizationId,
                patientId,
                conceptId,
                category = "imaging",
                valueText = "result",
            )
        }
        // invalid status
        assertFailsWith<DataAccessException> {
            insertObservation(
                organizationId,
                patientId,
                conceptId,
                status = "registered",
                valueText = "result",
            )
        }
    }

    @Test
    fun `observations must reference patient and encounter in the same organization`() {
        val northOrganizationId = insertOrganization()
        val southOrganizationId = insertOrganization()
        val northPatientId = insertPatient(northOrganizationId)
        val southPatientId = insertPatient(southOrganizationId)
        val conceptId = insertConcept()
        val northEncounterId = insertEncounter(northOrganizationId, northPatientId, conceptId)

        assertFailsWith<DataAccessException> {
            insertObservation(southOrganizationId, northPatientId, conceptId, valueText = "result")
        }
        assertFailsWith<DataAccessException> {
            insertObservation(
                southOrganizationId,
                southPatientId,
                conceptId,
                encounterId = northEncounterId,
                valueText = "result",
            )
        }
    }

    @Test
    fun `observation indexes start with organization for tenant scoped access`() {
        val indexDefinitions = jdbcTemplate.queryForList(
            """
            select indexdef
            from pg_indexes
            where schemaname = 'public'
              and tablename = 'observations'
              and indexname in (
                'observations_organization_patient_category_effective_idx',
                'observations_organization_patient_effective_idx',
                'observations_organization_encounter_idx',
                'observations_organization_code_idx'
              )
            """.trimIndent(),
            String::class.java,
        )

        assertEquals(4, indexDefinitions.size)
        assertTrue(indexDefinitions.all { it.contains("(organization_id") })
    }

    private fun insertOrganization(): UUID =
        jdbcTemplate.queryForObject(
            "insert into organizations (slug, display_name) values (?, ?) returning id",
            UUID::class.java,
            "observation-schema-org-${UUID.randomUUID()}",
            "Observation Schema Org",
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
            values ('http://loinc.org', ?, 'schema test observation')
            returning id
            """.trimIndent(),
            UUID::class.java,
            "OBS-SCHEMA-${UUID.randomUUID()}",
        )!!
        return jdbcTemplate.queryForObject(
            """
            insert into codeable_concepts (text, primary_coding_id)
            values ('schema test observation', ?)
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
            values (?, ?, 'planned', ?, now())
            returning id
            """.trimIndent(),
            UUID::class.java,
            organizationId,
            patientId,
            classConceptId,
        )!!

    private fun insertObservation(
        organizationId: UUID,
        patientId: UUID,
        codeConceptId: UUID,
        encounterId: UUID? = null,
        status: String = "final",
        category: String = "vital-signs",
        valueQuantity: BigDecimal? = null,
        valueQuantityUnit: String? = null,
        valueConceptId: UUID? = null,
        valueText: String? = null,
    ) {
        jdbcTemplate.update(
            """
            insert into observations (
              organization_id, patient_id, encounter_id, status, category, code_concept_id,
              value_quantity, value_quantity_unit, value_concept_id, value_text, effective_at
            )
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())
            """.trimIndent(),
            organizationId,
            patientId,
            encounterId,
            status,
            category,
            codeConceptId,
            valueQuantity,
            valueQuantityUnit,
            valueConceptId,
            valueText,
        )
    }
}
