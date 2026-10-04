package org.unividuell.mobility.manager

import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles

/**
 * Fails as soon as a migration adds a table [DatabaseCleaner] doesn't know, so the
 * cleanup order is extended right away instead of breaking tests by execution order.
 */
@SpringBootTest
@ActiveProfiles("test")
class DatabaseCleanerIntegrationTest @Autowired constructor(
    private val jdbc: JdbcTemplate,
) {

    @Test
    fun `the cleaner covers every domain table`() {
        val domainTables = jdbc.queryForList(
            """
            SELECT name FROM sqlite_master
            WHERE type = 'table'
              AND name NOT LIKE 'sqlite_%'
              AND name NOT LIKE 'SPRING_SESSION%'
              AND name <> 'flyway_schema_history'
            """.trimIndent(),
            String::class.java,
        )

        domainTables shouldContainExactlyInAnyOrder DatabaseCleaner.TABLES
    }
}
