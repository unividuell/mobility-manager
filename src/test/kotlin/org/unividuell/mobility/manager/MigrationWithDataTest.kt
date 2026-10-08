package org.unividuell.mobility.manager

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.SingleConnectionDataSource
import java.nio.file.Path
import java.util.UUID

/**
 * Migrations that rebuild a table run against a populated database here: the test
 * suite's own database is empty when Flyway runs, so it would never see a row move.
 */
class MigrationWithDataTest {

    @Test
    fun `V7 keys existing users by provider and subject and keeps every row that points at them`(@TempDir dir: Path) {
        val dataSource = SingleConnectionDataSource("jdbc:sqlite:${dir.resolve("mm.db")}?foreign_keys=true", true)
        try {
            val jdbc = JdbcTemplate(dataSource)
            flyway(dataSource, target = "6").migrate()
            jdbc.update("INSERT INTO users (id, github_id, login, display_name) VALUES (1, 4711, 'octocat', 'The Octocat'), (2, 1234, 'stranger', 'Stranger')")
            jdbc.update("INSERT INTO vehicles (id, name, color) VALUES (10, 'Kombi', '#06b6d4')")
            jdbc.update("INSERT INTO vehicle_managers (vehicle_id, user_id) VALUES (10, 1), (10, 2)")
            jdbc.update("INSERT INTO tags (user_id, name) VALUES (1, 'kupplung')")

            flyway(dataSource, target = "7").migrate()

            val rows = jdbc.queryForList("SELECT id, provider, subject, login, display_name, account_id FROM users ORDER BY id")
            rows.map { listOf(it["id"], it["provider"], it["subject"], it["login"], it["display_name"]) } shouldBe listOf(
                listOf(1, "github", "4711", "octocat", "The Octocat"),
                listOf(2, "github", "1234", "stranger", "Stranger"),
            )
            rows.map { UUID.fromString(it["account_id"] as String).version() } shouldBe listOf(4, 4)
            jdbc.queryForObject("SELECT COUNT(*) FROM vehicle_managers", Int::class.java) shouldBe 2
            jdbc.queryForObject("SELECT COUNT(*) FROM tags WHERE user_id = 1", Int::class.java) shouldBe 1
            jdbc.queryForList("PRAGMA foreign_key_check").shouldBeEmpty()
        } finally {
            dataSource.destroy()
        }
    }

    private fun flyway(dataSource: SingleConnectionDataSource, target: String): Flyway =
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").target(target).load()
}
