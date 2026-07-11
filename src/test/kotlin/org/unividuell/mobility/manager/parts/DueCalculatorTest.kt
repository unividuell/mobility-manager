package org.unividuell.mobility.manager.parts

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate

class DueCalculatorTest {

    private fun part(
        installedAtKm: Double,
        checkpoints: Set<PartCheckpoint>,
        retiredOn: LocalDate? = null,
    ) = Part(
        id = 1, vehicleId = 1, name = "Kupplung", details = null, priceCents = null,
        installedAtKm = installedAtKm, installedOn = LocalDate.of(2026, 7, 11),
        retiredOn = retiredOn, retiredAtKm = retiredOn?.let { installedAtKm },
        checkpoints = checkpoints,
    )

    @Test
    fun `flags overdue when the due reading is reached exactly, sorts overdue first`() {
        val p = part(
            installedAtKm = 19_000.0,
            checkpoints = setOf(
                PartCheckpoint(id = 1, offsetKm = 100.0, label = "Kontrolle"),      // due 19100 -> overdue
                PartCheckpoint(id = 2, offsetKm = 15_000.0, label = "Beläge"),      // due 34000 -> upcoming
                PartCheckpoint(id = 3, offsetKm = 123.0, label = "Exakt"),          // due 19123 == current -> overdue
            ),
        )

        val items = DueCalculator.openItems(listOf(p), currentKm = 19_123.0)

        items.map { it.checkpoint.label } shouldBe listOf("Kontrolle", "Exakt", "Beläge")
        items[0].overdue shouldBe true
        items[0].remainingKm shouldBe -23.0
        items[1].overdue shouldBe true
        items[1].remainingKm shouldBe 0.0
        items[2].overdue shouldBe false
        items[2].remainingKm shouldBe 14_877.0
        items[2].dueAtKm shouldBe 34_000.0
    }

    @Test
    fun `unknown current km yields items without a verdict, sorted by due reading`() {
        val p = part(
            installedAtKm = 19_000.0,
            checkpoints = setOf(
                PartCheckpoint(id = 1, offsetKm = 15_000.0, label = "Beläge"),
                PartCheckpoint(id = 2, offsetKm = 100.0, label = "Kontrolle"),
            ),
        )

        val items = DueCalculator.openItems(listOf(p), currentKm = null)

        items.map { it.checkpoint.label } shouldBe listOf("Kontrolle", "Beläge")
        items.all { it.remainingKm == null } shouldBe true
        items.none { it.overdue } shouldBe true
    }

    @Test
    fun `ignores retired parts and done checkpoints`() {
        val done = PartCheckpoint(id = 1, offsetKm = 100.0, label = "Erledigt", doneOn = LocalDate.of(2026, 7, 1), doneAtKm = 19_100.0)
        val activeWithDone = part(installedAtKm = 19_000.0, checkpoints = setOf(done))
        val retired = part(
            installedAtKm = 10_000.0,
            checkpoints = setOf(PartCheckpoint(id = 2, offsetKm = 100.0, label = "Alt")),
            retiredOn = LocalDate.of(2026, 7, 10),
        )

        DueCalculator.openItems(listOf(activeWithDone, retired), currentKm = 99_999.0).shouldBeEmpty()
    }
}
