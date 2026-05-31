package org.unividuell.mobility.manager.fuel

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import jakarta.servlet.http.Cookie
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockHttpServletRequestDsl
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.request.RequestPostProcessor
import org.unividuell.mobility.manager.user.AppUserRepository
import org.unividuell.mobility.manager.user.AppUserService
import org.unividuell.mobility.manager.vehicle.VehicleRepository
import org.unividuell.mobility.manager.vehicle.VehicleService
import java.time.LocalDate

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FuelControllerIntegrationTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val repository: FuelEntryRepository,
    private val vehicles: VehicleRepository,
    private val vehicleService: VehicleService,
    private val users: AppUserService,
    private val userRepository: AppUserRepository,
) {

    private val githubId = 4711L
    private var userId = 0L

    // Spring Session (JDBC) is cookie-based, so to keep the selected-vehicle context
    // across a test's requests we thread the SESSION cookie like a real browser would.
    private var sessionCookie: Cookie? = null

    @BeforeEach
    fun setUp() {
        repository.deleteAll()
        vehicles.deleteAll()
        userRepository.deleteAll()
        userId = users.upsert(githubId, login = "octocat", displayName = "The Octocat").id!!
        sessionCookie = null
    }

    private fun login(): RequestPostProcessor = oauth2Login().attributes { it["id"] = githubId }

    @Test
    fun `GET root renders a four-slot draft with the date prefilled and no vehicle quick-entry`() {
        vehicleService.create(userId, "Kombi", "#06b6d4") // sole vehicle → auto-selected as context

        val body = getRoot()

        // four slot tiles (liter, €/liter, km, date) — the vehicle slot is gone
        body.occurrencesOf("""data-testid="slot"""") shouldBe 4
        // three are empty; the date slot is prefilled with today
        body.occurrencesOf(">—<") shouldBe 3
        body shouldContain """data-testid="draft-panel""""
        body shouldContain """data-testid="value-input""""
        body shouldContain """name="date" value="${LocalDate.now()}""""
        body shouldContainAll listOf(
            """name="liters" value=""""",
            """name="pricePerLiter" value=""""",
            """name="kilometers" value=""""",
        )
        // the vehicle quick-entry and its datalist are removed
        body shouldNotContain "vehicle-options"
        body shouldNotContain """name="vehicleId""""
    }

    @Test
    fun `value below 5 is classified as PRICE_PER_LITER`() {
        vehicleService.create(userId, "Kombi", "#06b6d4")

        val body = postValue(value = "1.859")

        body shouldContain """name="pricePerLiter" value="1.859""""
        body shouldContain """name="liters" value="""""
        body shouldContain """name="kilometers" value="""""
    }

    @Test
    fun `value between 5 and 150 is classified as LITERS`() {
        vehicleService.create(userId, "Kombi", "#06b6d4")

        val body = postValue(value = "45.32")

        body shouldContain """name="liters" value="45.32""""
        body shouldContain """name="pricePerLiter" value="""""
    }

    @Test
    fun `value of 150 or more is classified as KILOMETERS`() {
        vehicleService.create(userId, "Kombi", "#06b6d4")

        val body = postValue(value = "520")

        body shouldContain """name="kilometers" value="520.0""""
    }

    @Test
    fun `german comma decimal is accepted and normalised to dot`() {
        vehicleService.create(userId, "Kombi", "#06b6d4")

        val body = postValue(value = "1,859")

        body shouldContain """name="pricePerLiter" value="1.859""""
    }

    @Test
    fun `classification falls back when primary slot already filled`() {
        vehicleService.create(userId, "Kombi", "#06b6d4")

        val body = postValue(value = "30", liters = "45")

        body shouldContain """name="pricePerLiter" value="30.0""""
        body shouldContain """name="liters" value="45.0""""
    }

    @Test
    fun `invalid (non-numeric, non-date) value leaves the draft unchanged`() {
        vehicleService.create(userId, "Kombi", "#06b6d4")

        val body = postValue(value = "abc", liters = "42.5")

        body shouldContain """name="liters" value="42.5""""
        body shouldContain """name="pricePerLiter" value="""""
        repository.count() shouldBe 0
    }

    @Test
    fun `negative or zero value is rejected without altering the draft`() {
        vehicleService.create(userId, "Kombi", "#06b6d4")

        val body = postValue(value = "-1", liters = "42.5")

        body shouldContain """name="liters" value="42.5""""
        body shouldContain """name="pricePerLiter" value="""""
    }

    @Test
    fun `the sole vehicle is the auto-selected context, so three numbers complete the entry`() {
        val vehicleId = vehicleService.create(userId, "Kombi", "#06b6d4").id!!

        postValue(value = "42.5")
        postValue(value = "680", liters = "42.5")
        withClue("no save yet — only two numbers") { repository.count() shouldBe 0 }

        // the third number completes it; the vehicle comes from the context
        val body = postValue(value = "1.749", liters = "42.5", kilometers = "680.0")

        body shouldContain """data-testid="result-panel""""
        body shouldContain "6.25" // 42.5 / 680 * 100
        body shouldContain "Kombi"

        repository.count() shouldBe 1
        val saved = repository.findAll().single()
        saved.vehicleId shouldBe vehicleId
        saved.date shouldBe LocalDate.now() // date defaulted to today
    }

    @Test
    fun `a typed past date overrides today's default and is persisted`() {
        val vehicleId = vehicleService.create(userId, "Kombi", "#06b6d4").id!!

        // type the date first (German format) — it overrides today's default
        var body = postValue(value = "20.05.2026")
        body shouldContain """name="date" value="2026-05-20""""
        body shouldContain "20.05.2026"

        postValue(value = "42.5", date = "2026-05-20")
        postValue(value = "680", liters = "42.5", date = "2026-05-20")
        body = postValue(value = "1.749", liters = "42.5", kilometers = "680.0", date = "2026-05-20")

        body shouldContain """data-testid="result-panel""""
        repository.count() shouldBe 1
        val saved = repository.findAll().single()
        saved.vehicleId shouldBe vehicleId
        saved.date shouldBe LocalDate.of(2026, 5, 20)
    }

    @Test
    fun `with several vehicles and none selected the entry is blocked`() {
        vehicleService.create(userId, "Kombi", "#06b6d4")
        vehicleService.create(userId, "Roadster", "#f43f5e")

        // nothing auto-selected → prompt to pick, no input field
        val panel = getRoot()
        panel shouldContain """data-testid="select-vehicle-hint""""
        panel shouldNotContain """data-testid="value-input""""

        // even posting all three numbers can't complete without a chosen vehicle
        postValue(value = "42.5")
        postValue(value = "680", liters = "42.5")
        postValue(value = "1.749", liters = "42.5", kilometers = "680.0")
        repository.count() shouldBe 0
    }

    @Test
    fun `selecting a vehicle sets the context, shows it in the header, and is used for the entry`() {
        val kombi = vehicleService.create(userId, "Kombi", "#06b6d4").id!!
        vehicleService.create(userId, "Roadster", "#f43f5e")

        selectVehicle(kombi)

        val home = getRoot()
        home shouldContain """data-testid="vehicle-context""""
        home shouldContain "Kombi"
        home shouldContain """data-testid="value-input""""

        postValue(value = "42.5")
        postValue(value = "680", liters = "42.5")
        postValue(value = "1.749", liters = "42.5", kilometers = "680.0")

        repository.count() shouldBe 1
        repository.findAll().single().vehicleId shouldBe kombi
    }

    @Test
    fun `reset endpoint returns an empty draft`() {
        vehicleService.create(userId, "Kombi", "#06b6d4")

        val body = capture(mockMvc.post("/fuel/reset") { common() }.andReturn())

        body shouldContainAll listOf(
            """name="liters" value=""""",
            """name="pricePerLiter" value=""""",
            """name="kilometers" value=""""",
        )
    }

    @Test
    fun `undo removes the caller's just-saved entry and returns an empty draft`() {
        val vehicleId = vehicleService.create(userId, "Kombi", "#06b6d4").id!!
        val saved = repository.save(entry(vehicleId))
        repository.count() shouldBe 1

        val body = postUndo(id = saved.id!!)

        repository.count() shouldBe 0
        body shouldContain """data-testid="draft-panel""""
    }

    @Test
    fun `undo does not delete an entry that belongs to another user's vehicle`() {
        val otherUserId = users.upsert(githubId = 1234L, login = "stranger", displayName = "Stranger").id!!
        val foreignVehicleId = vehicleService.create(otherUserId, "Fremder", "#f43f5e").id!!
        val foreign = repository.save(entry(foreignVehicleId))

        postUndo(id = foreign.id!!)

        withClue("the foreign entry must survive") { repository.count() shouldBe 1 }
    }

    private fun entry(vehicleId: Long) = FuelEntry(
        vehicleId = vehicleId,
        date = LocalDate.now(),
        liters = 42.5,
        pricePerLiter = 1.749,
        kilometers = 680.0,
    )

    private fun getRoot(): String = capture(mockMvc.get("/") { common() }.andReturn())

    private fun selectVehicle(id: Long) {
        capture(mockMvc.post("/vehicles/$id/select") { common() }.andReturn())
    }

    private fun postValue(
        value: String,
        liters: String = "",
        pricePerLiter: String = "",
        kilometers: String = "",
        date: String = "",
    ): String = capture(
        mockMvc.post("/fuel/value") {
            common()
            param("value", value)
            param("liters", liters)
            param("pricePerLiter", pricePerLiter)
            param("kilometers", kilometers)
            param("date", date)
        }.andReturn(),
    )

    private fun postUndo(id: Long): String = capture(
        mockMvc.post("/fuel/undo") {
            common()
            param("id", id.toString())
        }.andReturn(),
    )

    // ---- request plumbing: auth + carrying the Spring Session cookie ----

    private fun MockHttpServletRequestDsl.common() {
        with(login())
        sessionCookie?.let { cookie(it) }
    }

    private fun capture(result: MvcResult): String {
        result.response.getCookie("SESSION")?.let { sessionCookie = it }
        return result.response.contentAsString
    }

    // ---- tiny local helpers built on top of kotest matchers ----

    private fun String.occurrencesOf(needle: String): Int = split(needle).size - 1

    private infix fun String.shouldContainAll(needles: Iterable<String>) {
        needles.forEach { this shouldContain it }
    }
}
