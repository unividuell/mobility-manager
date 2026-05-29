package org.unividuell.mobility.manager.fuel

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import kotlin.test.assertEquals

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
        assertEquals(3, body.split("slot-value").size - 1)
        assertEquals(3, body.split(">—<").size - 1)
        assertContainsAll(
            body,
            """name="liters" value=""""",
            """name="pricePerLiter" value=""""",
            """name="kilometers" value=""""",
        )
    }

    @Test
    fun `value below 5 is classified as PRICE_PER_LITER`() {
        val body = postValue(value = "1.859")

        assertContains(body, """name="pricePerLiter" value="1.859"""")
        assertContains(body, """name="liters" value=""""")
        assertContains(body, """name="kilometers" value=""""")
    }

    @Test
    fun `value between 5 and 150 is classified as LITERS`() {
        val body = postValue(value = "45.32")

        assertContains(body, """name="liters" value="45.32"""")
        assertContains(body, """name="pricePerLiter" value=""""")
    }

    @Test
    fun `value of 150 or more is classified as KILOMETERS`() {
        val body = postValue(value = "520")

        assertContains(body, """name="kilometers" value="520.0"""")
    }

    @Test
    fun `german comma decimal is accepted and normalised to dot`() {
        val body = postValue(value = "1,859")

        assertContains(body, """name="pricePerLiter" value="1.859"""")
    }

    @Test
    fun `random-order entry produces a complete fuel entry and persists it`() {
        // 1) liters first
        var body = postValue(value = "42.5")
        assertContains(body, """name="liters" value="42.5"""")
        assertEquals(0, repository.count(), "no save yet — draft incomplete")

        // 2) kilometers next
        body = postValue(value = "680", liters = "42.5")
        assertContains(body, """name="kilometers" value="680.0"""")
        assertEquals(0, repository.count())

        // 3) price last → completes the entry
        body = postValue(value = "1.749", liters = "42.5", kilometers = "680.0")

        // result panel shown with calculated consumption + total cost
        assertContains(body, """class="result"""")
        assertContains(body, "6.25")     // 42.5 / 680 * 100
        assertContains(body, "74.33")    // 42.5 * 1.749

        // entry persisted
        assertEquals(1, repository.count())
        val saved = repository.findAll().single()
        assertEquals(42.5, saved.liters)
        assertEquals(1.749, saved.pricePerLiter)
        assertEquals(680.0, saved.kilometers)
    }

    @Test
    fun `classification falls back when primary slot already filled`() {
        // First value (45) lands in LITERS by magnitude.
        // Second value (30) would also be LITERS by magnitude, but that slot is
        // taken — fallback by log-distance to typicals (price=1.85, km=500)
        // makes PRICE_PER_LITER the closest match.
        val body = postValue(value = "30", liters = "45")

        assertContains(body, """name="pricePerLiter" value="30.0"""")
        assertContains(body, """name="liters" value="45.0"""")
    }

    @Test
    fun `invalid (non-numeric) value leaves the draft unchanged`() {
        val body = postValue(value = "abc", liters = "42.5")

        assertContains(body, """name="liters" value="42.5"""")
        assertContains(body, """name="pricePerLiter" value=""""")
        assertEquals(0, repository.count())
    }

    @Test
    fun `negative or zero value is rejected without altering the draft`() {
        val body = postValue(value = "-1", liters = "42.5")

        assertContains(body, """name="liters" value="42.5"""")
        assertContains(body, """name="pricePerLiter" value=""""")
    }

    @Test
    fun `reset endpoint returns an empty draft`() {
        val body = mockMvc.post("/fuel/reset").andReturn().response.contentAsString

        assertContainsAll(
            body,
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

    private fun assertContains(haystack: String, needle: String) {
        check(haystack.contains(needle)) {
            "expected response to contain:\n  $needle\n--- actual (first 2000 chars) ---\n${haystack.take(2000)}"
        }
    }

    private fun assertContainsAll(haystack: String, vararg needles: String) {
        needles.forEach { assertContains(haystack, it) }
    }
}
