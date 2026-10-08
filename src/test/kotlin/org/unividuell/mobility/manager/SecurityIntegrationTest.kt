package org.unividuell.mobility.manager

import io.kotest.matchers.string.shouldContain
import jakarta.servlet.http.Cookie
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.unividuell.mobility.manager.user.AppUser
import org.unividuell.mobility.manager.user.AppUserService
import org.unividuell.mobility.manager.user.TEST_CSRF_TOKEN
import org.unividuell.mobility.manager.user.principal
import org.unividuell.mobility.manager.user.signedInAs
import org.unividuell.mobility.manager.user.withCsrfToken

/**
 * Where this server-rendered app turns the auth lib's single-page-app defaults back into
 * page navigation, and the CSRF protection the lib switches on.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SecurityIntegrationTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val users: AppUserService,
    private val db: DatabaseCleaner,
) {

    private lateinit var user: AppUser

    @BeforeEach
    fun setUp() {
        db.clean()
        user = users.upsert(provider = "github", subject = "4711", login = "octocat", name = "The Octocat")
    }

    @Test
    fun `an anonymous page request is sent to the login page, not answered with 401`() {
        mockMvc.get("/vehicles").andExpect {
            status { isFound() }
            redirectedUrl("/login")
        }
    }

    @Test
    fun `an htmx request after the session has gone is sent to the login page`() {
        mockMvc.post("/fuel/value") {
            header("HX-Request", "true")
            param("value", "45")
            with(withCsrfToken())
        }.andExpect {
            status { isFound() }
            redirectedUrl("/login")
        }
    }

    @Test
    fun `with the test login off, the sign-in starts at GitHub`() {
        mockMvc.get("/login/start").andExpect {
            status { isFound() }
            redirectedUrl("/oauth2/authorization/github")
        }
    }

    @Test
    fun `logout lands on the login page`() {
        mockMvc.post("/logout") { with(signedInAs(user)) }.andExpect {
            status { isFound() }
            redirectedUrl("/login")
        }
    }

    @Test
    fun `a state-changing request without a CSRF token is refused`() {
        mockMvc.post("/fuel/reset") { with(oauth2Login().oauth2User(user.principal())) }.andExpect {
            status { isForbidden() }
        }
    }

    @Test
    fun `a token in the header that differs from the cookie is refused`() {
        mockMvc.post("/fuel/reset") {
            with(oauth2Login().oauth2User(user.principal()))
            cookie(Cookie("XSRF-TOKEN", "the-cookie-token"))
            header("X-XSRF-TOKEN", "another-token")
        }.andExpect {
            status { isForbidden() }
        }
    }

    @Test
    fun `a state-changing request with the token in the htmx header goes through`() {
        mockMvc.post("/fuel/reset") { with(signedInAs(user)) }.andExpect {
            status { isOk() }
        }
    }

    @Test
    fun `the header shows the signed-in user's name`() {
        val body = mockMvc.get("/vehicles") { with(signedInAs(user)) }.andReturn().response.contentAsString

        body shouldContain "The Octocat"
    }

    @Test
    fun `the login page starts the sign-in through the auth lib`() {
        val body = mockMvc.get("/login").andReturn().response.contentAsString

        body shouldContain """href="/login/start""""
    }

    @Test
    fun `the htmx pages hand htmx the CSRF token as a request header`() {
        for (page in listOf("/", "/vehicles")) {
            val body = mockMvc.get(page) { with(signedInAs(user)) }.andReturn().response.contentAsString

            body shouldContain """hx-headers="{&quot;X-XSRF-TOKEN&quot;: &quot;$TEST_CSRF_TOKEN&quot;}""""
        }
    }

    @Test
    fun `the logout form carries the CSRF token`() {
        val body = mockMvc.get("/vehicles") { with(signedInAs(user)) }.andReturn().response.contentAsString

        body shouldContain """<input type="hidden" name="_csrf" value="$TEST_CSRF_TOKEN"/>"""
    }
}
