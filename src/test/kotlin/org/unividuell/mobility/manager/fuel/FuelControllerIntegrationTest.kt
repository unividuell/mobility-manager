package org.unividuell.mobility.manager.fuel

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post

@SpringBootTest
@AutoConfigureMockMvc
class FuelControllerIntegrationTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val repository: FuelEntryRepository,
) {

    @BeforeEach
    fun cleanDb() {
        repository.deleteAll()
    }

    @Test
    fun `GET root renders empty draft panel with three empty slots`() {
        val body = mockMvc.get("/").andReturn().response.contentAsString

        // three slot tiles + the input form
        body.occurrencesOf("""data-testid="slot"""") shouldBe 3
        body.occurrencesOf(">—<") shouldBe 3
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
    fun `random-order entry produces a complete fuel entry and persists it`() {
        // 1) liters first
        var body = postValue(value = "42.5")
        body shouldContain """name="liters" value="42.5""""
        withClue("no save yet — draft incomplete") {
            repository.count() shouldBe 0
        }

        // 2) kilometers next
        body = postValue(value = "680", liters = "42.5")
        body shouldContain """name="kilometers" value="680.0""""
        repository.count() shouldBe 0

        // 3) price last → completes the entry
        body = postValue(value = "1.749", liters = "42.5", kilometers = "680.0")

        // result panel shown with calculated consumption + total cost
        body shouldContain """data-testid="result-panel""""
        body shouldContain "6.25"     // 42.5 / 680 * 100
        body shouldContain "74.33"    // 42.5 * 1.749

        // entry persisted
        repository.count() shouldBe 1
        val saved = repository.findAll().single()
        saved.liters shouldBe 42.5
        saved.pricePerLiter shouldBe 1.749
        saved.kilometers shouldBe 680.0
    }

    @Test
    fun `classification falls back when primary slot already filled`() {
        // First value (45) lands in LITERS by magnitude.
        // Second value (30) would also be LITERS by magnitude, but that slot is
        // taken — fallback by log-distance to typicals (price=1.85, km=500)
        // makes PRICE_PER_LITER the closest match.
        val body = postValue(value = "30", liters = "45")

        body shouldContain """name="pricePerLiter" value="30.0""""
        body shouldContain """name="liters" value="45.0""""
    }

    @Test
    fun `invalid (non-numeric) value leaves the draft unchanged`() {
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
        val body = mockMvc.post("/fuel/reset").andReturn().response.contentAsString

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
    ): String = mockMvc.post("/fuel/value") {
        param("value", value)
        param("liters", liters)
        param("pricePerLiter", pricePerLiter)
        param("kilometers", kilometers)
    }.andReturn().response.contentAsString

    // ---- tiny local helpers built on top of kotest matchers ----

    private fun String.occurrencesOf(needle: String): Int = split(needle).size - 1

    private infix fun String.shouldContainAll(needles: Iterable<String>) {
        needles.forEach { this shouldContain it }
    }
}
