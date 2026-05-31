package org.unividuell.mobility.manager.fuel

import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate

/**
 * Unit test for [FuelCalculator], which resolves the driven distance and
 * consumption per refueling for both vehicle modes.
 */
class FuelCalculatorTest {

    private fun trip(id: Long, date: LocalDate, liters: Double, kilometers: Double) =
        FuelEntry(id = id, vehicleId = 1L, date = date, liters = liters, pricePerLiter = 1.7, kilometers = kilometers)

    private fun reading(id: Long, date: LocalDate, liters: Double, odometer: Double) =
        FuelEntry(id = id, vehicleId = 1L, date = date, liters = liters, pricePerLiter = 1.7, odometer = odometer)

    @Test
    fun `trip-meter entries use their own distance`() {
        val points = FuelCalculator.resolve(
            listOf(trip(1L, LocalDate.of(2026, 1, 1), liters = 45.0, kilometers = 600.0)),
        )

        points.single().distanceKm shouldBe 600.0
        points.single().consumptionPer100Km!! shouldBe (7.5 plusOrMinus 1e-9)
    }

    @Test
    fun `odometer entries derive distance from the previous reading, the first has none`() {
        // input newest-first, as the repository hands it over
        val newest = reading(3L, LocalDate.of(2026, 3, 1), liters = 42.0, odometer = 51_400.0)
        val middle = reading(2L, LocalDate.of(2026, 2, 1), liters = 45.0, odometer = 50_800.0)
        val oldest = reading(1L, LocalDate.of(2026, 1, 1), liters = 40.0, odometer = 50_000.0)

        val points = FuelCalculator.resolve(listOf(newest, middle, oldest))

        // order is preserved (still newest-first)
        points.map { it.id } shouldBe listOf(3L, 2L, 1L)
        points[2].distanceKm.shouldBeNull()                       // oldest: no predecessor
        points[2].consumptionPer100Km.shouldBeNull()
        points[1].distanceKm!! shouldBe (800.0 plusOrMinus 1e-9)  // 50800 - 50000
        points[1].consumptionPer100Km!! shouldBe (45.0 / 800.0 * 100.0 plusOrMinus 1e-9)
        points[0].distanceKm!! shouldBe (600.0 plusOrMinus 1e-9)  // 51400 - 50800
    }

    @Test
    fun `a back-dated odometer entry re-derives its neighbours' distances`() {
        val first = reading(1L, LocalDate.of(2026, 1, 1), liters = 40.0, odometer = 50_000.0)
        val third = reading(3L, LocalDate.of(2026, 3, 1), liters = 42.0, odometer = 51_400.0)
        // without the middle reading, the March entry measures across the whole gap
        FuelCalculator.resolve(listOf(third, first))[0].distanceKm!! shouldBe (1_400.0 plusOrMinus 1e-9)

        // insert a reading dated in February: March now measures only against it
        val second = reading(2L, LocalDate.of(2026, 2, 1), liters = 45.0, odometer = 50_800.0)
        val points = FuelCalculator.resolve(listOf(third, second, first))
        points.first { it.id == 3L }.distanceKm!! shouldBe (600.0 plusOrMinus 1e-9)   // 51400 - 50800
        points.first { it.id == 2L }.distanceKm!! shouldBe (800.0 plusOrMinus 1e-9)   // 50800 - 50000
    }

    @Test
    fun `an entry with both readings uses the trip distance but still feeds the odometer chain`() {
        val first = reading(1L, LocalDate.of(2026, 1, 1), liters = 40.0, odometer = 50_000.0)
        // a trip-meter entry that ALSO carries an odometer reading
        val both = FuelEntry(id = 2L, vehicleId = 1L, date = LocalDate.of(2026, 2, 1), liters = 45.0, pricePerLiter = 1.7, kilometers = 600.0, odometer = 50_800.0)
        val later = reading(3L, LocalDate.of(2026, 3, 1), liters = 42.0, odometer = 51_400.0)

        val points = FuelCalculator.resolve(listOf(later, both, first))

        // the middle entry's consumption uses its trip distance (600), not the gap (800)
        points.first { it.id == 2L }.distanceKm!! shouldBe (600.0 plusOrMinus 1e-9)
        points.first { it.id == 2L }.consumptionPer100Km!! shouldBe (45.0 / 600.0 * 100.0 plusOrMinus 1e-9)
        // but its odometer reading still anchors the next entry's gap (51400 - 50800)
        points.first { it.id == 3L }.distanceKm!! shouldBe (600.0 plusOrMinus 1e-9)
    }

    @Test
    fun `a non-increasing odometer reading yields no distance`() {
        val first = reading(1L, LocalDate.of(2026, 1, 1), liters = 40.0, odometer = 50_000.0)
        val typo = reading(2L, LocalDate.of(2026, 2, 1), liters = 45.0, odometer = 49_900.0) // lower than before

        val points = FuelCalculator.resolve(listOf(typo, first))

        points.first { it.id == 2L }.distanceKm.shouldBeNull()
        points.first { it.id == 2L }.consumptionPer100Km.shouldBeNull()
    }

    // For the outlier cases km is fixed at 100 so consumption (L/100km) equals litres.
    private fun consumptionEntries(vararg liters: Double): List<FuelEntry> =
        liters.mapIndexed { i, l -> trip(id = (i + 1).toLong(), date = LocalDate.of(2026, 1, i + 1), liters = l, kilometers = 100.0) }

    @Test
    fun `flags a high consumption outlier once there are at least five refuelings`() {
        val points = FuelCalculator.resolve(consumptionEntries(6.0, 6.1, 6.2, 6.3, 6.4, 12.8))

        points.count { it.isOutlier } shouldBe 1
        points.first { it.isOutlier }.consumptionPer100Km!! shouldBe (12.8 plusOrMinus 1e-9)
    }

    @Test
    fun `flags a low consumption outlier too`() {
        val points = FuelCalculator.resolve(consumptionEntries(6.0, 6.1, 6.2, 6.3, 6.4, 2.0))

        points.count { it.isOutlier } shouldBe 1
        points.first { it.isOutlier }.consumptionPer100Km!! shouldBe (2.0 plusOrMinus 1e-9)
    }

    @Test
    fun `does not flag anything with fewer than five refuelings`() {
        // the 12.8 is wildly off but there isn't enough history to judge yet
        val points = FuelCalculator.resolve(consumptionEntries(6.0, 6.1, 6.2, 12.8))

        points.count { it.isOutlier } shouldBe 0
    }

    @Test
    fun `identical consumptions yield no outliers`() {
        // MAD (and mean abs deviation) are 0 here; must not divide-by-zero into all-outliers
        val points = FuelCalculator.resolve(consumptionEntries(6.0, 6.0, 6.0, 6.0, 6.0, 6.0))

        points.count { it.isOutlier } shouldBe 0
    }
}
