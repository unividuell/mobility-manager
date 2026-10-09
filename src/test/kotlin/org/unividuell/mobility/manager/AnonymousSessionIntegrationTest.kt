package org.unividuell.mobility.manager

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

/**
 * Anonymous requests must never create (and persist) a session — otherwise the
 * SQLite store fills up with empty 30-day sessions: the container healthcheck polls
 * /actuator/health every 30s without cookies, and bots hit protected routes whose
 * 302 to /login must not stash a saved request in a session (the auth lib keeps the
 * page to return to in a cookie).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AnonymousSessionIntegrationTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val jdbc: JdbcTemplate,
) {

    @Test
    fun `an anonymous request to a protected route does not create a session`() {
        val before = sessionCount()

        val response = mockMvc.get("/")
            .andReturn().response

        response.status shouldBe 302
        response.getCookie("SESSION") shouldBe null
        sessionCount() shouldBe before
    }

    @Test
    fun `an anonymous health probe does not create a session`() {
        val before = sessionCount()

        val response = mockMvc.get("/actuator/health")
            .andReturn().response

        response.status shouldBe 200
        response.getCookie("SESSION") shouldBe null
        sessionCount() shouldBe before
    }

    private fun sessionCount(): Int =
        jdbc.queryForObject("SELECT COUNT(*) FROM SPRING_SESSION", Int::class.java)!!
}
