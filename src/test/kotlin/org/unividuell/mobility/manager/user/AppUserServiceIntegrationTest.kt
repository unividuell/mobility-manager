package org.unividuell.mobility.manager.user

import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
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
class AppUserServiceIntegrationTest @Autowired constructor(
    private val service: AppUserService,
    private val repository: AppUserRepository,
    private val jdbc: JdbcTemplate,
    private val db: DatabaseCleaner,
) {

    @BeforeEach
    fun cleanDb() {
        db.clean()
    }

    @Test
    fun `first sign-in creates the user`() {
        val user = service.upsert(provider = "github", subject = "4711", login = "octocat", name = "The Octocat")

        user.id.shouldNotBeNull()
        user.provider shouldBe "github"
        user.subject shouldBe "4711"
        user.login shouldBe "octocat"
        user.displayName shouldBe "The Octocat"
        repository.count() shouldBe 1
    }

    @Test
    fun `repeat sign-in updates mirrored fields and keeps the account id`() {
        val first = service.upsert(provider = "github", subject = "4711", login = "octocat", name = "The Octocat")
        val second = service.upsert(provider = "github", subject = "4711", login = "octocat-renamed", name = "Mona Lisa")

        repository.count() shouldBe 1
        second.id shouldBe first.id
        second.accountId shouldBe first.accountId
        second.login shouldBe "octocat-renamed"
        second.displayName shouldBe "Mona Lisa"
    }

    @Test
    fun `the same login from two providers is two accounts`() {
        val github = service.upsert(provider = "github", subject = "4711", login = "prof", name = null)
        val test = service.upsert(provider = "test", subject = "prof", login = "prof", name = null)

        test.id shouldNotBe github.id
        test.accountId shouldNotBe github.accountId
    }

    @Test
    fun `a missing or blank name falls back to the login`() {
        service.upsert(provider = "github", subject = "1", login = "noname", name = null).displayName shouldBe "noname"
        service.upsert(provider = "github", subject = "2", login = "blank", name = " ").displayName shouldBe "blank"
    }

    @Test
    fun `a user V7 migrated signs in to the same account`() {
        val migrated = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO users (account_id, provider, subject, login, display_name) VALUES (?, 'github', '868171', 'cleemansen', 'Clemens')",
            migrated.toString(),
        )

        val user = service.upsert(provider = "github", subject = "868171", login = "cleemansen", name = "Clemens")

        repository.count() shouldBe 1
        user.accountId shouldBe migrated
    }
}
