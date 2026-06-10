package dev.ehr.provenance

import dev.ehr.testsupport.PostgresIntegrationTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID
import kotlin.test.assertFailsWith

class ProvenanceSchemaMigrationTest : PostgresIntegrationTest() {
    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Test
    fun `creates provenance and revision tables with spine shape`() {
        val provenanceColumns = columnNullability("provenance_events")
        assertEquals("NO", provenanceColumns["organization_id"])
        assertEquals("NO", provenanceColumns["patient_id"])
        assertEquals("NO", provenanceColumns["target_resource_type"])
        assertEquals("NO", provenanceColumns["target_resource_id"])
        assertEquals("NO", provenanceColumns["target_version"])
        assertEquals("NO", provenanceColumns["activity"])
        assertEquals("NO", provenanceColumns["source_type"])
        assertEquals("NO", provenanceColumns["recorded_at"])
        assertEquals("YES", provenanceColumns["agent_user_id"])
        assertEquals("YES", provenanceColumns["prior_resource_version"])
        assertEquals("YES", provenanceColumns["synthetic_generation_run_id"])

        val revisionColumns = columnNullability("resource_revisions")
        assertEquals("NO", revisionColumns["organization_id"])
        assertEquals("NO", revisionColumns["patient_id"])
        assertEquals("NO", revisionColumns["resource_type"])
        assertEquals("NO", revisionColumns["resource_id"])
        assertEquals("NO", revisionColumns["version"])
        assertEquals("NO", revisionColumns["snapshot"])
    }

    @Test
    fun `provenance vocabulary constraints fail at database layer`() {
        val organizationId = insertOrganization()

        assertFailsWith<DataAccessException> {
            insertProvenance(organizationId, activity = "imported")
        }
        assertFailsWith<DataAccessException> {
            insertProvenance(organizationId, sourceType = "doctor")
        }
        assertFailsWith<DataAccessException> {
            insertProvenance(organizationId, targetVersion = 0)
        }
    }

    @Test
    fun `provenance and revision rows are append only`() {
        val organizationId = insertOrganization()
        val provenanceId = insertProvenance(organizationId)
        val revisionId = insertRevision(organizationId)

        assertFailsWith<DataAccessException> {
            jdbcTemplate.update("update provenance_events set activity = 'updated' where id = ?", provenanceId)
        }
        assertFailsWith<DataAccessException> {
            jdbcTemplate.update("delete from provenance_events where id = ?", provenanceId)
        }
        assertFailsWith<DataAccessException> {
            jdbcTemplate.update("update resource_revisions set version = 99 where id = ?", revisionId)
        }
        assertFailsWith<DataAccessException> {
            jdbcTemplate.update("delete from resource_revisions where id = ?", revisionId)
        }
    }

    @Test
    fun `revisions are unique per resource and version`() {
        val organizationId = insertOrganization()
        val resourceId = UUID.randomUUID()
        insertRevision(organizationId, resourceId = resourceId, version = 1)

        assertFailsWith<DataAccessException> {
            insertRevision(organizationId, resourceId = resourceId, version = 1)
        }
    }

    @Test
    fun `history indexes start with organization`() {
        val indexDefinitions = jdbcTemplate.queryForList(
            """
            select indexdef
            from pg_indexes
            where schemaname = 'public'
              and indexname in (
                'provenance_events_organization_target_idx',
                'provenance_events_organization_patient_time_idx',
                'resource_revisions_organization_patient_time_idx'
              )
            """.trimIndent(),
            String::class.java,
        )

        assertEquals(3, indexDefinitions.size)
        assertTrue(indexDefinitions.all { it.contains("(organization_id") })
    }

    private fun columnNullability(table: String): Map<String, String> =
        jdbcTemplate.query(
            """
            select column_name, is_nullable
            from information_schema.columns
            where table_schema = 'public'
              and table_name = ?
            """.trimIndent(),
            { rs, _ -> rs.getString("column_name") to rs.getString("is_nullable") },
            table,
        ).toMap()

    private fun insertOrganization(): UUID =
        jdbcTemplate.queryForObject(
            "insert into organizations (slug, display_name) values (?, ?) returning id",
            UUID::class.java,
            "prov-schema-org-${UUID.randomUUID()}",
            "Prov Schema Org",
        )!!

    private fun insertProvenance(
        organizationId: UUID,
        activity: String = "created",
        sourceType: String = "clinician-authored",
        targetVersion: Int = 1,
    ): UUID =
        jdbcTemplate.queryForObject(
            """
            insert into provenance_events (
              organization_id, patient_id, target_resource_type, target_resource_id,
              target_version, activity, source_type
            )
            values (?, ?, 'PATIENT', ?, ?, ?, ?)
            returning id
            """.trimIndent(),
            UUID::class.java,
            organizationId,
            UUID.randomUUID(),
            UUID.randomUUID(),
            targetVersion,
            activity,
            sourceType,
        )!!

    private fun insertRevision(
        organizationId: UUID,
        resourceId: UUID = UUID.randomUUID(),
        version: Int = 1,
    ): UUID =
        jdbcTemplate.queryForObject(
            """
            insert into resource_revisions (
              organization_id, patient_id, resource_type, resource_id, version, snapshot
            )
            values (?, ?, 'ENCOUNTER', ?, ?, '{"status":"planned"}'::jsonb)
            returning id
            """.trimIndent(),
            UUID::class.java,
            organizationId,
            UUID.randomUUID(),
            resourceId,
            version,
        )!!
}
