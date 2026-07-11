package org.unividuell.mobility.manager.fuel

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.unividuell.mobility.manager.vehicle.Vehicle
import java.time.LocalDate

class CurrentKmCalculatorTest {

    private fun tripVehicle(baselineKm: Double? = null, baselineOn: LocalDate? = null) =
        Vehicle(id = 1, name = "Moped", color = "#fff", hasTripMeter = true, baselineKm = baselineKm, baselineOn = baselineOn)

    private fun totalVehicle() =
        Vehicle(id = 2, name = "Kombi", color = "#fff", hasTripMeter = false)

    private fun trip(date: LocalDate, km: Double) =
        FuelEntry(vehicleId = 1, date = date, liters = 5.0, pricePerLiter = 1.7, kilometers = km)

    private fun reading(date: LocalDate, odo: Double) =
        FuelEntry(vehicleId = 2, date = date, liters = 40.0, pricePerLiter = 1.7, odometer = odo)

    @Test
    fun `total-only vehicle uses the highest odometer reading`() {
        val entries = listOf(reading(LocalDate.of(2026, 6, 1), 50_100.0), reading(LocalDate.of(2026, 7, 1), 50_800.0))
        CurrentKmCalculator.currentKm(totalVehicle(), entries) shouldBe 50_800.0
    }

    @Test
    fun `total-only vehicle without readings is unknown`() {
        CurrentKmCalculator.currentKm(totalVehicle(), emptyList()) shouldBe null
    }

    @Test
    fun `trip-meter vehicle sums trips strictly after the baseline date`() {
        val vehicle = tripVehicle(baselineKm = 19_000.0, baselineOn = LocalDate.of(2026, 7, 1))
        val entries = listOf(
            trip(LocalDate.of(2026, 6, 30), 200.0), // before the anchor -> already in the reading
            trip(LocalDate.of(2026, 7, 1), 50.0),   // ON the anchor day -> already in the reading
            trip(LocalDate.of(2026, 7, 5), 100.0),
            trip(LocalDate.of(2026, 7, 9), 23.0),
        )
        CurrentKmCalculator.currentKm(vehicle, entries) shouldBe 19_123.0
    }

    @Test
    fun `trip-meter vehicle without a baseline is unknown`() {
        CurrentKmCalculator.currentKm(tripVehicle(), listOf(trip(LocalDate.of(2026, 7, 5), 100.0))) shouldBe null
    }
}
