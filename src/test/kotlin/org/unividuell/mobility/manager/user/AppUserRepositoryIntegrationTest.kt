package org.unividuell.mobility.manager.user

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.unividuell.mobility.manager.DatabaseCleaner
import java.util.UUID

@SpringBootTest
@ActiveProfiles("test")
class AppUserRepositoryIntegrationTest @Autowired constructor(
    private val repository: AppUserRepository,
    private val jdbc: JdbcTemplate,
    private val db: DatabaseCleaner,
) {

    @BeforeEach
    fun cleanDb() {
        db.clean()
    }

    @Test
    fun `account_id is stored as canonical UUID text, the form V7 writes`() {
        val accountId = UUID.randomUUID()
        repository.save(
            AppUser(accountId = accountId, provider = "github", subject = "4711", login = "octocat", displayName = "The Octocat"),
        )

        val row = jdbc.queryForMap("SELECT typeof(account_id) AS type, account_id FROM users")
        row["type"] shouldBe "text"
        row["account_id"] shouldBe accountId.toString()
    }

    @Test
    fun `a row V7 migrated is found by its account id`() {
        val accountId = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO users (account_id, provider, subject, login, display_name) VALUES (?, 'github', '4711', 'octocat', 'The Octocat')",
            accountId.toString(),
        )

        repository.findByAccountId(accountId)?.login shouldBe "octocat"
    }
}
