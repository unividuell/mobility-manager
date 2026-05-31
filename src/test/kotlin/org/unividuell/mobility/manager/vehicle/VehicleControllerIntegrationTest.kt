package org.unividuell.mobility.manager.vehicle

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.request.RequestPostProcessor
import org.unividuell.mobility.manager.fuel.FuelEntryRepository
import org.unividuell.mobility.manager.user.AppUserRepository
import org.unividuell.mobility.manager.user.AppUserService

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class VehicleControllerIntegrationTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val vehicles: VehicleRepository,
    private val service: VehicleService,
    private val users: AppUserService,
    private val userRepository: AppUserRepository,
    private val fuelEntries: FuelEntryRepository,
) {

    private val githubId = 4711L
    private var userId = 0L

    @BeforeEach
    fun setUp() {
        // dependency order: fuel_entries -> vehicles (cascades vehicle_managers) -> users
        fuelEntries.deleteAll()
        vehicles.deleteAll()
        userRepository.deleteAll()
        userId = users.upsert(githubId, login = "octocat", displayName = "The Octocat").id!!
    }

    private fun login(): RequestPostProcessor = oauth2Login().attributes { it["id"] = githubId }

    @Test
    fun `index shows the empty state, then the created vehicle`() {
        mockMvc.get("/vehicles") { with(login()) }
            .andReturn().response.contentAsString shouldContain "Noch keine Fahrzeuge"

        service.create(userId, "Kombi", "#06b6d4")

        val body = mockMvc.get("/vehicles") { with(login()) }.andReturn().response.contentAsString
        body shouldContain "Kombi"
        body shouldContain "#06b6d4"
    }

    @Test
    fun `create persists a vehicle for the current user and redirects`() {
        mockMvc.post("/vehicles") {
            with(login())
            param("name", "Roadster")
            param("color", "#f43f5e")
        }.andExpect { status { is3xxRedirection() } }

        val mine = service.listFor(userId)
        mine.map { it.name } shouldBe listOf("Roadster")
        mine.single().color shouldBe "#f43f5e"
    }

    @Test
    fun `delete removes the vehicle`() {
        val id = service.create(userId, "Gone", "#06b6d4").id!!

        mockMvc.delete("/vehicles/$id") { with(login()) }
            .andExpect { status { isOk() } }

        vehicles.count() shouldBe 0
    }

    @Test
    fun `select sets the active vehicle context, marked in the overview`() {
        val kombi = service.create(userId, "Kombi", "#06b6d4").id!!
        service.create(userId, "Roadster", "#f43f5e")

        // Spring Session is cookie-based; carry the SESSION cookie to the next request.
        val select = mockMvc.post("/vehicles/$kombi/select") { with(login()) }
            .andExpect { status { is3xxRedirection() } }
            .andReturn()
        val sessionCookie = select.response.getCookie("SESSION")!!

        val body = mockMvc.get("/vehicles") {
            with(login())
            cookie(sessionCookie)
        }.andReturn().response.contentAsString

        // exactly one card is the active context; the other still offers "Wählen"
        body.occurrencesOf("""data-testid="active-vehicle"""") shouldBe 1
        body.occurrencesOf("""data-testid="select-vehicle"""") shouldBe 1
    }

    @Test
    fun `select rejects a vehicle the user does not manage`() {
        val otherUserId = users.upsert(githubId = 2222L, login = "stranger", displayName = "Stranger").id!!
        val foreign = service.create(otherUserId, "Fremder", "#f43f5e").id!!

        mockMvc.post("/vehicles/$foreign/select") { with(login()) }
            .andExpect { status { isNotFound() } }
    }

    private fun String.occurrencesOf(needle: String): Int = split(needle).size - 1
}
