package org.unividuell.mobility.manager.parts

import io.kotest.matchers.shouldBe
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
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.request.RequestPostProcessor
import org.unividuell.mobility.manager.DatabaseCleaner
import org.unividuell.mobility.manager.fuel.FuelEntryRepository
import org.unividuell.mobility.manager.user.AppUserService
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
    private val fuelEntries: FuelEntryRepository,
    private val users: AppUserService,
    private val db: DatabaseCleaner,
) {

    private val githubId = 4711L
    private var userId = 0L
    private var vehicleId = 0L

    @BeforeEach
    fun setUp() {
        db.clean()
        userId = users.upsert(provider = "github", subject = githubId.toString(), login = "octocat", name = "The Octocat").id!!
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
        val strangerId = users.upsert(provider = "github", subject = "2222", login = "stranger", name = "Stranger").id!!
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

    @Test
    fun `header badge appears on any page once the selected vehicle has overdue items`() {
        createClutch() // due at 19_223
        fuelEntries.save(
            org.unividuell.mobility.manager.fuel.FuelEntry(
                vehicleId = vehicleId, date = LocalDate.of(2026, 8, 1),
                liters = 5.0, pricePerLiter = 1.7, odometer = 19_300.0,
            ),
        )
        // select the vehicle as the session context; carry the SESSION cookie on
        val select = mockMvc.post("/vehicles/$vehicleId/select") { with(login()) }.andReturn()
        val sessionCookie = select.response.getCookie("SESSION")!!

        val body = mockMvc.get("/vehicles") {
            with(login())
            cookie(sessionCookie)
        }.andReturn().response.contentAsString

        body shouldContain """data-testid="overdue-badge""""
        body shouldContain "/vehicles/$vehicleId/parts"
    }

    @Test
    fun `header badge stays away without overdue items`() {
        createClutch() // no fuel data -> unknown km -> no verdict, no badge
        val select = mockMvc.post("/vehicles/$vehicleId/select") { with(login()) }.andReturn()
        val sessionCookie = select.response.getCookie("SESSION")!!

        val body = mockMvc.get("/vehicles") {
            with(login())
            cookie(sessionCookie)
        }.andReturn().response.contentAsString

        body shouldNotContain """data-testid="overdue-badge""""
    }

    @Test
    fun `create persists a part from the form and redirects to the list`() {
        mockMvc.post("/vehicles/$vehicleId/parts") {
            with(login())
            param("name", "Kupplung Sachs")
            param("details", "verstärkte Ausführung")
            param("priceEuro", "250")
            param("installedAtKm", "19123")
            param("installedOn", "2026-07-11")
            param("tags", "Kupplung, Sachs")
            param("checkpointOffsetKm", "100", "")      // one filled row, one empty row (dropped)
            param("checkpointLabel", "Kontrolle", "")
        }.andExpect { status { is3xxRedirection() } }

        val saved = parts.findAllByVehicleId(vehicleId).single()
        saved.name shouldBe "Kupplung Sachs"
        saved.priceCents shouldBe 25_000
        saved.checkpoints.single().label shouldBe "Kontrolle"
        tags.findAllByUserIdOrderByName(userId).map { it.name } shouldBe listOf("kupplung", "sachs")
    }

    @Test
    fun `create rejects a half-filled checkpoint row with 400`() {
        mockMvc.post("/vehicles/$vehicleId/parts") {
            with(login())
            param("name", "Kette")
            param("installedAtKm", "100")
            param("installedOn", "2026-07-11")
            param("checkpointOffsetKm", "500")
            param("checkpointLabel", "")               // offset without label
        }.andExpect { status { isBadRequest() } }
    }

    @Test
    fun `edit form is prefilled and update rewrites open checkpoints`() {
        val created = createClutch()

        val body = mockMvc.get("/vehicles/$vehicleId/parts/${created.id}/edit") { with(login()) }
            .andReturn().response.contentAsString
        body shouldContain "Kupplung Sachs"
        body shouldContain "kupplung"

        mockMvc.post("/vehicles/$vehicleId/parts/${created.id}") {
            with(login())
            param("name", "Kupplung Sachs Plus")
            param("priceEuro", "250")
            param("installedAtKm", "19123")
            param("installedOn", "2026-07-11")
            param("tags", "kupplung")
            param("checkpointOffsetKm", "20000")
            param("checkpointLabel", "Großinspektion")
        }.andExpect { status { is3xxRedirection() } }

        val updated = parts.findById(created.id!!).orElseThrow()
        updated.name shouldBe "Kupplung Sachs Plus"
        updated.checkpoints.single().label shouldBe "Großinspektion"
    }

    @Test
    fun `delete removes the part from the list`() {
        val created = createClutch()

        mockMvc.post("/vehicles/$vehicleId/parts/${created.id}/delete") { with(login()) }
            .andExpect { status { is3xxRedirection() } }

        parts.count() shouldBe 0
    }

    @Test
    fun `form POSTs 404 for a foreign vehicle`() {
        val strangerId = users.upsert(provider = "github", subject = "2222", login = "stranger", name = "Stranger").id!!
        val foreign = vehicleService.create(strangerId, "Fremd", "#f43f5e").id!!

        mockMvc.post("/vehicles/$foreign/parts") {
            with(login())
            param("name", "Hijack")
            param("installedAtKm", "1")
            param("installedOn", "2026-07-11")
        }.andExpect { status { isNotFound() } }
    }

    @Test
    fun `checking off posts the confirmed values and redirects to the list`() {
        val created = createClutch()
        val checkpoint = created.checkpoints.single()

        mockMvc.post("/vehicles/$vehicleId/parts/${created.id}/checkpoints/${checkpoint.id}/done") {
            with(login())
            param("doneOn", "2026-07-20")
            param("doneAtKm", "19250")
        }.andExpect { status { is3xxRedirection() } }

        val done = parts.findById(created.id!!).orElseThrow().checkpoints.single()
        done.doneOn shouldBe LocalDate.of(2026, 7, 20)
        done.doneAtKm shouldBe 19_250.0
        // done checkpoints leave the due list
        mockMvc.get("/vehicles/$vehicleId/parts") { with(login()) }
            .andReturn().response.contentAsString shouldNotContain "Abhaken"
    }

    @Test
    fun `checking off without a reading stores only the date`() {
        val created = createClutch()
        val checkpoint = created.checkpoints.single()

        mockMvc.post("/vehicles/$vehicleId/parts/${created.id}/checkpoints/${checkpoint.id}/done") {
            with(login())
            param("doneOn", "2026-07-20")
        }.andExpect { status { is3xxRedirection() } }

        val done = parts.findById(created.id!!).orElseThrow().checkpoints.single()
        done.doneOn shouldBe LocalDate.of(2026, 7, 20)
        done.doneAtKm shouldBe null
    }

    @Test
    fun `checking off a foreign vehicle's checkpoint 404s`() {
        val strangerId = users.upsert(provider = "github", subject = "2222", login = "stranger", name = "Stranger").id!!
        val foreignVehicle = vehicleService.create(strangerId, "Fremd", "#f43f5e").id!!
        val foreignPart = partService.create(
            strangerId, foreignVehicle, name = "Fremdteil", details = null, priceEuro = null,
            installedAtKm = 1.0, installedOn = LocalDate.of(2026, 7, 1),
            tagNames = emptyList(), checkpoints = listOf(PartService.CheckpointInput(100.0, "X")),
        )

        mockMvc.post("/vehicles/$foreignVehicle/parts/${foreignPart.id}/checkpoints/${foreignPart.checkpoints.single().id}/done") {
            with(login())
            param("doneOn", "2026-07-20")
        }.andExpect { status { isNotFound() } }
    }

    @Test
    fun `replace form is prefilled from the predecessor and carries the hidden id`() {
        val old = createClutch()

        val body = mockMvc.get("/vehicles/$vehicleId/parts/new") {
            with(login())
            param("replaces", old.id.toString())
        }.andReturn().response.contentAsString

        body shouldContain "ersetzt"
        body shouldContain "Kupplung Sachs"                      // prefilled name
        body shouldContain """name="replacesPartId""""
        body shouldContain """value="${old.id}""""
    }

    @Test
    fun `posting with replacesPartId retires the old part and shows both on the page`() {
        val old = createClutch()

        mockMvc.post("/vehicles/$vehicleId/parts") {
            with(login())
            param("name", "Kupplung LUK")
            param("installedAtKm", "25000")
            param("installedOn", "2027-01-15")
            param("tags", "kupplung")
            param("replacesPartId", old.id.toString())
        }.andExpect { status { is3xxRedirection() } }

        val body = mockMvc.get("/vehicles/$vehicleId/parts") { with(login()) }.andReturn().response.contentAsString
        body shouldContain "Kupplung LUK"                        // active card
        body shouldContain "Historie"                            // retired section appeared
        body shouldContain "ersetzt durch Kupplung LUK"
        parts.findById(old.id!!).orElseThrow().active shouldBe false
    }

    @Test
    fun `due items link to the part's edit form`() {
        val created = createClutch()

        val body = mockMvc.get("/vehicles/$vehicleId/parts") { with(login()) }.andReturn().response.contentAsString

        body shouldContain """data-testid="due-edit-link""""
        body shouldContain "/vehicles/$vehicleId/parts/${created.id}/edit"
    }
}
