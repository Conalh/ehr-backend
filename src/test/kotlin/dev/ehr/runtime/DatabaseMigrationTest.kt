package dev.ehr.runtime

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.jdbc.core.JdbcTemplate
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

@SpringBootTest
@Testcontainers
class DatabaseMigrationTest {
    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Test
    fun `flyway applies runtime skeleton migration`() {
        val marker = jdbcTemplate.queryForObject(
            "select name from runtime_markers where name = ?",
            String::class.java,
            "runtime-skeleton",
        )

        assertEquals("runtime-skeleton", marker)
    }

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("ehr_core_test")
            .withUsername("ehr_core")
            .withPassword("ehr_core_dev")
    }
}
