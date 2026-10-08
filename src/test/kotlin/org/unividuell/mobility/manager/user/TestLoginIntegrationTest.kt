package org.unividuell.mobility.manager.user

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import jakarta.servlet.http.Cookie
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.unividuell.auth.test.withCsrfToken
import org.unividuell.mobility.manager.DatabaseCleaner

/**
 * The local door: without a profile, the auth lib's test-user picker signs a test user in
 * through [AppUserService], exactly like GitHub does in production. No @ActiveProfiles("test")
 * on purpose — any active profile would demand a test-login key — so the in-memory datasource
 * is set here instead of in application-test.yaml.
 */
@SpringBootTest(
    properties = [
        "spring.datasource.url=jdbc:sqlite:file::memory:?cache=shared&date_class=text&date_string_format=yyyy-MM-dd&foreign_keys=true",
        "spring.datasource.hikari.maximum-pool-size=5",
    ],
)
@AutoConfigureMockMvc
class TestLoginIntegrationTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val repository: AppUserRepository,
    private val db: DatabaseCleaner,
) {

    @BeforeEach
    fun cleanDb() {
        db.clean()
    }

    @Test
    fun `the sign-in starts at the test-user picker`() {
        val body = mockMvc.get("/login/start").andReturn().response.contentAsString

        body shouldContain """name="login" value="Fry""""
    }

    @Test
    fun `picking a test user provisions an account and signs it in`() {
        val signIn = mockMvc.post("/login/test/as") {
            param("login", "Fry")
            with(withCsrfToken())
        }.andReturn().response

        signIn.status shouldBe 302
        signIn.redirectedUrl shouldBe "/"
        repository.findByProviderAndSubject("test", "Fry")?.displayName shouldBe "Fry"

        val session = Cookie("SESSION", signIn.getCookie("SESSION")!!.value)
        mockMvc.get("/vehicles") { cookie(session) }.andReturn().response.status shouldBe 200
    }

    @Test
    fun `the picker returns to the page that sent the visitor to the login page`() {
        val remembered = mockMvc.get("/vehicles").andReturn().response.getCookie("REDIRECT_URI")!!

        mockMvc.post("/login/test/as") {
            cookie(remembered)
            param("login", "Fry")
            with(withCsrfToken())
        }.andReturn().response.redirectedUrl shouldBe "/vehicles"
    }
}
