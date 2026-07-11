package org.unividuell.mobility.manager.parts

import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.request.RequestPostProcessor
import org.unividuell.mobility.manager.fuel.FuelEntryRepository
import org.unividuell.mobility.manager.user.AppUserRepository
import org.unividuell.mobility.manager.user.AppUserService
import org.unividuell.mobility.manager.vehicle.VehicleRepository
import org.unividuell.mobility.manager.vehicle.VehicleService
import java.time.LocalDate

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PartControllerIntegrationTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val partService: PartService,
    private val parts: PartRepository,
    private val tags: TagRepository,
    private val vehicleService: VehicleService,
    private val vehicles: VehicleRepository,
    private val fuelEntries: FuelEntryRepository,
    private val users: AppUserService,
    private val userRepository: AppUserRepository,
) {

    private val githubId = 4711L
    private var userId = 0L
    private var vehicleId = 0L

    @BeforeEach
    fun setUp() {
        // dependency order: parts -> tags -> fuel_entries -> vehicles -> users
        parts.deleteAll()
        tags.deleteAll()
        fuelEntries.deleteAll()
        vehicles.deleteAll()
        userRepository.deleteAll()
        userId = users.upsert(githubId, login = "octocat", displayName = "The Octocat").id!!
        vehicleId = vehicleService.create(userId, "Moped", "#06b6d4", hasTripMeter = false).id!!
    }

    private fun login(): RequestPostProcessor = oauth2Login().attributes { it["id"] = githubId }

    private fun createClutch(): Part = partService.create(
        userId, vehicleId, name = "Kupplung Sachs", details = "verstärkt", priceEuro = 250,
        installedAtKm = 19_123.0, installedOn = LocalDate.of(2026, 7, 11),
        tagNames = listOf("kupplung"),
        checkpoints = listOf(PartService.CheckpointInput(100.0, "Kontrolle")),
    )

    @Test
    fun `parts page shows the empty state, then the active part with tag and price`() {
        mockMvc.get("/vehicles/$vehicleId/parts") { with(login()) }
            .andReturn().response.contentAsString shouldContain "Noch keine Teile"

        createClutch()

        val body = mockMvc.get("/vehicles/$vehicleId/parts") { with(login()) }.andReturn().response.contentAsString
        body shouldContain "Kupplung Sachs"
        body shouldContain "kupplung"
        body shouldContain "250"
        body shouldContain "Kontrolle"
    }

    @Test
    fun `parts page marks an overdue checkpoint`() {
        createClutch()
        // total-only vehicle: an odometer reading beyond due (19_223) makes it overdue
        fuelEntries.save(
            org.unividuell.mobility.manager.fuel.FuelEntry(
                vehicleId = vehicleId, date = LocalDate.of(2026, 8, 1),
                liters = 5.0, pricePerLiter = 1.7, odometer = 19_300.0,
            ),
        )

        val body = mockMvc.get("/vehicles/$vehicleId/parts") { with(login()) }.andReturn().response.contentAsString
        body shouldContain """data-testid="due-overdue""""
    }

    @Test
    fun `parts page hints at the missing baseline instead of judging`() {
        // trip-meter vehicle without a baseline -> unknown current km
        val tripVehicleId = vehicleService.create(userId, "Trippy", "#10b981", hasTripMeter = true).id!!
        partService.create(
            userId, tripVehicleId, name = "Kette", details = null, priceEuro = null,
            installedAtKm = 5_000.0, installedOn = LocalDate.of(2026, 7, 1),
            tagNames = emptyList(), checkpoints = listOf(PartService.CheckpointInput(500.0, "Spannung prüfen")),
        )

        val body = mockMvc.get("/vehicles/$tripVehicleId/parts") { with(login()) }.andReturn().response.contentAsString
        body shouldContain "Tacho-Ablesung"
        body shouldNotContain """data-testid="due-overdue""""
    }

    @Test
    fun `parts page 404s for a foreign vehicle`() {
        val strangerId = users.upsert(2222L, login = "stranger", displayName = "Stranger").id!!
        val foreign = vehicleService.create(strangerId, "Fremd", "#f43f5e").id!!

        mockMvc.get("/vehicles/$foreign/parts") { with(login()) }
            .andExpect { status { isNotFound() } }
    }

    @Test
    fun `vehicle overview links each vehicle to its parts page`() {
        val body = mockMvc.get("/vehicles") { with(login()) }.andReturn().response.contentAsString
        body shouldContain """data-testid="parts-list-link""""
        body shouldContain "/vehicles/$vehicleId/parts"
    }
}
