package org.unividuell.mobility.manager.user

import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

@SpringBootTest
class AppUserServiceIntegrationTest @Autowired constructor(
    private val service: AppUserService,
    private val repository: AppUserRepository,
) {

    @BeforeEach
    fun cleanDb() {
        repository.deleteAll()
    }

    @Test
    fun `first login creates the user`() {
        val user = service.upsert(githubId = 4711L, login = "octocat", displayName = "The Octocat")

        user.id.shouldNotBeNull()
        user.githubId shouldBe 4711L
        user.login shouldBe "octocat"
        user.displayName shouldBe "The Octocat"
        repository.count() shouldBe 1
    }

    @Test
    fun `repeat login updates mirrored fields without creating a duplicate`() {
        val first = service.upsert(githubId = 4711L, login = "octocat", displayName = "The Octocat")
        val second = service.upsert(githubId = 4711L, login = "octocat-renamed", displayName = "Mona Lisa")

        repository.count() shouldBe 1
        second.id shouldBe first.id
        second.login shouldBe "octocat-renamed"
        second.displayName shouldBe "Mona Lisa"
    }
}
