package org.unividuell.mobility.manager

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.session.Session
import org.springframework.session.SessionRepository
import org.springframework.test.context.ActiveProfiles

/**
 * Proves that HTTP sessions are persisted to SQLite (spring-session-jdbc), which is
 * what lets a logged-in user survive an app restart. A session attribute is written
 * as a BLOB and read back through a fresh repository query.
 */
@SpringBootTest
@ActiveProfiles("test")
class SpringSessionPersistenceIntegrationTest @Autowired constructor(
    // the wired bean is JdbcIndexedSessionRepository whose JdbcSession type is
    // package-private, so we work through the SessionRepository interface
    private val sessions: SessionRepository<*>,
) {

    @Test
    fun `a session attribute round-trips through the SQLite-backed store`() {
        roundTrip(sessions)
    }

    private fun <S : Session> roundTrip(repo: SessionRepository<S>) {
        val session = repo.createSession()
        session.setAttribute("greeting", "hello")
        repo.save(session)

        val loaded = repo.findById(session.id)
        loaded.shouldNotBeNull()
        loaded.getAttribute<String>("greeting") shouldBe "hello"

        repo.deleteById(session.id)
        repo.findById(session.id).shouldBeNull()
    }
}
