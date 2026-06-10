package dev.ehr.allergy

import dev.ehr.testsupport.PostgresIntegrationTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID
import kotlin.test.assertFailsWith

class AllergySchemaMigrationTest : PostgresIntegrationTest() {
    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Test
    fun `creates allergies table with compartment and coded shape`() {
        val columns = jdbcTemplate.query(
            """
            select column_name, is_nullable
            from information_schema.columns
            where table_schema = 'public'
              and table_name = 'allergies'
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
        assertEquals("YES", columns["category"])
        assertEquals("YES", columns["criticality"])
        assertEquals("YES", columns["onset_date"])
        assertEquals("NO", columns["recorded_at"])
        assertEquals("NO", columns["version"])
    }

    @Test
    fun `allergy indexes start with organization for tenant scoped access`() {
        val indexDefinitions = jdbcTemplate.queryForList(
            """
            select indexdef
            from pg_indexes
            where schemaname = 'public'
              and tablename = 'allergies'
              and indexname in (
                'allergies_organization_patient_recorded_idx',
                'allergies_organization_clinical_status_idx',
                'allergies_organization_encounter_idx',
                'allergies_organization_code_idx'
              )
            """.trimIndent(),
            String::class.java,
        )

        assertEquals(4, indexDefinitions.size)
        assertTrue(indexDefinitions.all { it.contains("(organization_id") })
    }

    @Test
    fun `allergy constraints fail at database layer`() {
        val organizationId = insertOrganization()
        val patientId = insertPatient(organizationId)
        val conceptId = insertConcept()

        assertFailsWith<DataAccessException> {
            insertAllergy(organizationId, patientId, conceptId, clinicalStatus = "remission")
        }
        assertFailsWith<DataAccessException> {
            insertAllergy(organizationId, patientId, conceptId, verificationStatus = "provisional")
        }
        assertFailsWith<DataAccessException> {
            insertAllergy(organizationId, patientId, conceptId, category = "chemical")
        }
        assertFailsWith<DataAccessException> {
            insertAllergy(organizationId, patientId, conceptId, criticality = "severe")
        }
        assertFailsWith<DataAccessException> {
            jdbcTemplate.update(
                """
                insert into allergies (organization_id, patient_id, code_concept_id, version)
                values (?, ?, ?, 0)
                """.trimIndent(),
                organizationId,
                patientId,
                conceptId,
            )
        }
    }

    @Test
    fun `allergies must reference patient and encounter in the same organization`() {
        val northOrganizationId = insertOrganization()
        val southOrganizationId = insertOrganization()
        val northPatientId = insertPatient(northOrganizationId)
        val southPatientId = insertPatient(southOrganizationId)
        val conceptId = insertConcept()
        val northEncounterId = insertEncounter(northOrganizationId, northPatientId, conceptId)

        assertFailsWith<DataAccessException> {
            insertAllergy(southOrganizationId, northPatientId, conceptId)
        }
        assertFailsWith<DataAccessException> {
            insertAllergy(southOrganizationId, southPatientId, conceptId, encounterId = northEncounterId)
        }
    }

    @Test
    fun `allergies expose a composite organization id key for future references`() {
        val constraints = jdbcTemplate.queryForList(
            """
            select constraint_name
            from information_schema.table_constraints
            where table_schema = 'public'
              and table_name = 'allergies'
              and constraint_type = 'UNIQUE'
            """.trimIndent(),
            String::class.java,
        )

        assertTrue("allergies_organization_id_id_key" in constraints)
    }

    private fun insertOrganization(): UUID =
        jdbcTemplate.queryForObject(
            "insert into organizations (slug, display_name) values (?, ?) returning id",
            UUID::class.java,
            "allergy-schema-org-${UUID.randomUUID()}",
            "Allergy Schema Org",
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
            values ('http://snomed.info/sct', ?, 'schema test allergen')
            returning id
            """.trimIndent(),
            UUID::class.java,
            "ALLERGY-SCHEMA-${UUID.randomUUID()}",
        )!!
        return jdbcTemplate.queryForObject(
            """
            insert into codeable_concepts (text, primary_coding_id)
            values ('schema test allergen', ?)
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

    private fun insertAllergy(
        organizationId: UUID,
        patientId: UUID,
        codeConceptId: UUID,
        encounterId: UUID? = null,
        clinicalStatus: String = "active",
        verificationStatus: String = "confirmed",
        category: String? = null,
        criticality: String? = null,
    ) {
        jdbcTemplate.update(
            """
            insert into allergies (
              organization_id, patient_id, encounter_id, clinical_status,
              verification_status, code_concept_id, category, criticality
            )
            values (?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            organizationId,
            patientId,
            encounterId,
            clinicalStatus,
            verificationStatus,
            codeConceptId,
            category,
            criticality,
        )
    }
}
