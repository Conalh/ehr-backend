package dev.ehr.order

import dev.ehr.testsupport.PostgresIntegrationTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID
import kotlin.test.assertFailsWith

class OrderSchemaMigrationTest : PostgresIntegrationTest() {
    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Test
    fun `creates orders table with compartment and lifecycle shape`() {
        val columns = jdbcTemplate.query(
            """
            select column_name, is_nullable
            from information_schema.columns
            where table_schema = 'public'
              and table_name = 'orders'
            order by ordinal_position
            """.trimIndent(),
            { rs, _ -> rs.getString("column_name") to rs.getString("is_nullable") },
        ).toMap()

        assertEquals("NO", columns["organization_id"])
        assertEquals("NO", columns["patient_id"])
        assertEquals("YES", columns["encounter_id"])
        assertEquals("NO", columns["status"])
        assertEquals("NO", columns["code_concept_id"])
        assertEquals("YES", columns["priority"])
        assertEquals("NO", columns["placed_at"])
        assertEquals("NO", columns["version"])
    }

    @Test
    fun `order constraints fail at database layer`() {
        val organizationId = insertOrganization()
        val patientId = insertPatient(organizationId)
        val conceptId = insertConcept()

        assertFailsWith<DataAccessException> {
            insertOrder(organizationId, patientId, conceptId, status = "draft")
        }
        assertFailsWith<DataAccessException> {
            insertOrder(organizationId, patientId, conceptId, priority = "asap")
        }
    }

    @Test
    fun `orders must reference patient in the same organization`() {
        val northOrganizationId = insertOrganization()
        val southOrganizationId = insertOrganization()
        val northPatientId = insertPatient(northOrganizationId)
        val conceptId = insertConcept()

        assertFailsWith<DataAccessException> {
            insertOrder(southOrganizationId, northPatientId, conceptId)
        }
    }

    @Test
    fun `order indexes start with organization and composite key exists`() {
        val indexDefinitions = jdbcTemplate.queryForList(
            """
            select indexdef
            from pg_indexes
            where schemaname = 'public'
              and tablename = 'orders'
              and indexname in (
                'orders_organization_patient_placed_idx',
                'orders_organization_status_idx',
                'orders_organization_code_idx'
              )
            """.trimIndent(),
            String::class.java,
        )
        assertEquals(3, indexDefinitions.size)
        assertTrue(indexDefinitions.all { it.contains("(organization_id") })

        val constraints = jdbcTemplate.queryForList(
            """
            select constraint_name
            from information_schema.table_constraints
            where table_schema = 'public'
              and table_name = 'orders'
              and constraint_type = 'UNIQUE'
            """.trimIndent(),
            String::class.java,
        )
        assertTrue("orders_organization_id_id_key" in constraints)
    }

    private fun insertOrganization(): UUID =
        jdbcTemplate.queryForObject(
            "insert into organizations (slug, display_name) values (?, ?) returning id",
            UUID::class.java,
            "order-schema-org-${UUID.randomUUID()}",
            "Order Schema Org",
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
            values ('http://loinc.org', ?, 'schema test orderable')
            returning id
            """.trimIndent(),
            UUID::class.java,
            "ORDER-SCHEMA-${UUID.randomUUID()}",
        )!!
        return jdbcTemplate.queryForObject(
            """
            insert into codeable_concepts (text, primary_coding_id)
            values ('schema test orderable', ?)
            returning id
            """.trimIndent(),
            UUID::class.java,
            codingId,
        )!!
    }

    private fun insertOrder(
        organizationId: UUID,
        patientId: UUID,
        codeConceptId: UUID,
        status: String = "active",
        priority: String? = null,
    ) {
        jdbcTemplate.update(
            """
            insert into orders (organization_id, patient_id, status, code_concept_id, priority)
            values (?, ?, ?, ?, ?)
            """.trimIndent(),
            organizationId,
            patientId,
            status,
            codeConceptId,
            priority,
        )
    }
}
