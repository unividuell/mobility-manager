package org.unividuell.mobility.manager.fuel

import io.kotest.assertions.withClue
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
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.request.RequestPostProcessor
import org.unividuell.mobility.manager.user.AppUserRepository
import org.unividuell.mobility.manager.user.AppUserService
import org.unividuell.mobility.manager.vehicle.VehicleRepository
import org.unividuell.mobility.manager.vehicle.VehicleService

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

    @BeforeEach
    fun setUp() {
        repository.deleteAll()
        vehicles.deleteAll()
        userRepository.deleteAll()
        userId = users.upsert(githubId, login = "octocat", displayName = "The Octocat").id!!
    }

    private fun login(): RequestPostProcessor = oauth2Login().attributes { it["id"] = githubId }

    @Test
    fun `GET root renders empty draft panel with four empty slots`() {
        val body = mockMvc.get("/") { with(login()) }.andReturn().response.contentAsString

        // four slot tiles (liter, €/liter, km, vehicle) + the input form
        body.occurrencesOf("""data-testid="slot"""") shouldBe 4
        body.occurrencesOf(">—<") shouldBe 4
        body shouldContain """data-testid="draft-panel""""
        body shouldContainAll listOf(
            """name="liters" value=""""",
            """name="pricePerLiter" value=""""",
            """name="kilometers" value=""""",
        )
    }

    @Test
    fun `value below 5 is classified as PRICE_PER_LITER`() {
        val body = postValue(value = "1.859")

        body shouldContain """name="pricePerLiter" value="1.859""""
        body shouldContain """name="liters" value="""""
        body shouldContain """name="kilometers" value="""""
    }

    @Test
    fun `value between 5 and 150 is classified as LITERS`() {
        val body = postValue(value = "45.32")

        body shouldContain """name="liters" value="45.32""""
        body shouldContain """name="pricePerLiter" value="""""
    }

    @Test
    fun `value of 150 or more is classified as KILOMETERS`() {
        val body = postValue(value = "520")

        body shouldContain """name="kilometers" value="520.0""""
    }

    @Test
    fun `german comma decimal is accepted and normalised to dot`() {
        val body = postValue(value = "1,859")

        body shouldContain """name="pricePerLiter" value="1.859""""
    }

    @Test
    fun `the only vehicle is preselected, so three numbers complete the entry`() {
        val vehicleId = vehicleService.create(userId, "Kombi", "#06b6d4").id!!

        // the sole vehicle is already picked on a fresh panel
        val panel = mockMvc.get("/") { with(login()) }.andReturn().response.contentAsString
        panel shouldContain """name="vehicleId" value="$vehicleId""""
        panel shouldContain "Kombi"

        postValue(value = "42.5")
        postValue(value = "680", liters = "42.5")
        withClue("no save yet — only two numbers") { repository.count() shouldBe 0 }

        // third number completes it; no vehicle entry needed
        val body = postValue(value = "1.749", liters = "42.5", kilometers = "680.0")

        body shouldContain """data-testid="result-panel""""
        body shouldContain "6.25"     // 42.5 / 680 * 100
        body shouldContain "Kombi"

        repository.count() shouldBe 1
        repository.findAll().single().vehicleId shouldBe vehicleId
    }

    @Test
    fun `with several vehicles none is preselected and the substring-typed one is linked`() {
        val kombi = vehicleService.create(userId, "Kombi", "#06b6d4").id!!
        vehicleService.create(userId, "Roadster", "#f43f5e")

        // multiple vehicles → nothing preselected
        val panel = mockMvc.get("/") { with(login()) }.andReturn().response.contentAsString
        panel shouldContain """name="vehicleId" value="""""

        postValue(value = "42.5")
        postValue(value = "680", liters = "42.5")
        // three numbers but no vehicle yet → still a draft, not saved
        var body = postValue(value = "1.749", liters = "42.5", kilometers = "680.0")
        body shouldContain """data-testid="draft-panel""""
        repository.count() shouldBe 0

        // a substring resolves the right vehicle and completes the entry
        body = postValue(value = "omb", liters = "42.5", pricePerLiter = "1.749", kilometers = "680.0")

        body shouldContain """data-testid="result-panel""""
        body shouldContain "Kombi"
        repository.count() shouldBe 1
        repository.findAll().single().vehicleId shouldBe kombi
    }

    @Test
    fun `classification falls back when primary slot already filled`() {
        val body = postValue(value = "30", liters = "45")

        body shouldContain """name="pricePerLiter" value="30.0""""
        body shouldContain """name="liters" value="45.0""""
    }

    @Test
    fun `invalid (non-numeric, non-matching) value leaves the draft unchanged`() {
        val body = postValue(value = "abc", liters = "42.5")

        body shouldContain """name="liters" value="42.5""""
        body shouldContain """name="pricePerLiter" value="""""
        repository.count() shouldBe 0
    }

    @Test
    fun `negative or zero value is rejected without altering the draft`() {
        val body = postValue(value = "-1", liters = "42.5")

        body shouldContain """name="liters" value="42.5""""
        body shouldContain """name="pricePerLiter" value="""""
    }

    @Test
    fun `reset endpoint returns an empty draft`() {
        val body = mockMvc.post("/fuel/reset") { with(login()) }.andReturn().response.contentAsString

        body shouldContainAll listOf(
            """name="liters" value=""""",
            """name="pricePerLiter" value=""""",
            """name="kilometers" value=""""",
        )
    }

    private fun postValue(
        value: String,
        liters: String = "",
        pricePerLiter: String = "",
        kilometers: String = "",
        vehicleId: String = "",
    ): String = mockMvc.post("/fuel/value") {
        with(login())
        param("value", value)
        param("liters", liters)
        param("pricePerLiter", pricePerLiter)
        param("kilometers", kilometers)
        param("vehicleId", vehicleId)
    }.andReturn().response.contentAsString

    // ---- tiny local helpers built on top of kotest matchers ----

    private fun String.occurrencesOf(needle: String): Int = split(needle).size - 1

    private infix fun String.shouldContainAll(needles: Iterable<String>) {
        needles.forEach { this shouldContain it }
    }
}
