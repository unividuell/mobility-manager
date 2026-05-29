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
}
