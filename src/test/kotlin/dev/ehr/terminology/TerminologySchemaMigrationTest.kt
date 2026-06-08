package dev.ehr.terminology

import dev.ehr.testsupport.PostgresIntegrationTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID
import kotlin.test.assertFailsWith

class TerminologySchemaMigrationTest : PostgresIntegrationTest() {
    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Test
    fun `creates terminology foundation tables`() {
        val tables = jdbcTemplate.queryForList(
            """
            select table_name
            from information_schema.tables
            where table_schema = 'public'
              and table_name in (
                'code_systems',
                'code_system_versions',
                'codings',
                'codeable_concepts',
                'codeable_concept_codings',
                'value_sets',
                'value_set_versions',
                'value_set_members',
                'terminology_import_runs'
              )
            order by table_name
            """.trimIndent(),
            String::class.java,
        )

        assertEquals(
            listOf(
                "code_system_versions",
                "code_systems",
                "codeable_concept_codings",
                "codeable_concepts",
                "codings",
                "terminology_import_runs",
                "value_set_members",
                "value_set_versions",
                "value_sets",
            ),
            tables,
        )
    }

    @Test
    fun `enforces nonblank canonical system and coding fields`() {
        assertFailsWith<DataAccessException> {
            jdbcTemplate.update(
                "insert into code_systems (canonical_uri, name) values (?, ?)",
                "   ",
                "Blank System",
            )
        }

        assertFailsWith<DataAccessException> {
            jdbcTemplate.update(
                "insert into codings (system, code) values (?, ?)",
                "   ",
                "12345",
            )
        }

        assertFailsWith<DataAccessException> {
            jdbcTemplate.update(
                "insert into codings (system, code) values (?, ?)",
                CanonicalCodeSystems.LOINC,
                "   ",
            )
        }

        assertFailsWith<DataAccessException> {
            jdbcTemplate.update(
                "insert into codings (system, code, display) values (?, ?, ?)",
                CanonicalCodeSystems.LOINC,
                "85354-9",
                "   ",
            )
        }
    }

    @Test
    fun `value set members must reference existing value set versions`() {
        assertFailsWith<DataAccessException> {
            jdbcTemplate.update(
                """
                insert into value_set_members (
                  value_set_version_id,
                  system,
                  code
                ) values (?, ?, ?)
                """.trimIndent(),
                UUID.randomUUID(),
                CanonicalCodeSystems.SNOMED_CT,
                "73211009",
            )
        }
    }
}
