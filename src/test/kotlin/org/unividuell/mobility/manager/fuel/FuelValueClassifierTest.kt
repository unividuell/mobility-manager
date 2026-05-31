package org.unividuell.mobility.manager.fuel

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Unit test for the mode-aware [FuelValueClassifier]. The price/liters buckets are
 * shared; only the third (distance) slot differs by vehicle: a trip distance for
 * trip-meter vehicles, an absolute odometer reading otherwise.
 */
class FuelValueClassifierTest {

    private val classifier = FuelValueClassifier()

    @Nested
    inner class TripMeterVehicle {

        @Test
        fun `a large value is the trip distance`() {
            classifier.classify(520.0, emptySet(), hasTripMeter = true) shouldBe FuelField.KILOMETERS
        }

        @Test
        fun `price and liters keep their buckets`() {
            classifier.classify(1.859, emptySet(), hasTripMeter = true) shouldBe FuelField.PRICE_PER_LITER
            classifier.classify(45.0, emptySet(), hasTripMeter = true) shouldBe FuelField.LITERS
        }

        @Test
        fun `the odometer slot is never offered`() {
            // even a typical odometer-sized value lands in the trip slot for these vehicles
            classifier.classify(123_456.0, emptySet(), hasTripMeter = true) shouldBe FuelField.KILOMETERS
        }
    }

    @Nested
    inner class TotalOnlyVehicle {

        @Test
        fun `a large value is the odometer reading`() {
            classifier.classify(123_456.0, emptySet(), hasTripMeter = false) shouldBe FuelField.ODOMETER
            // the boundary value that would be a trip distance also maps to the odometer slot
            classifier.classify(520.0, emptySet(), hasTripMeter = false) shouldBe FuelField.ODOMETER
        }

        @Test
        fun `price and liters keep their buckets`() {
            classifier.classify(1.859, emptySet(), hasTripMeter = false) shouldBe FuelField.PRICE_PER_LITER
            classifier.classify(45.0, emptySet(), hasTripMeter = false) shouldBe FuelField.LITERS
        }

        @Test
        fun `the trip-distance slot is never offered`() {
            classifier.classify(
                123_456.0,
                alreadyFilled = setOf(FuelField.PRICE_PER_LITER, FuelField.LITERS),
                hasTripMeter = false,
            ) shouldBe FuelField.ODOMETER
        }

        @Test
        fun `falls back to the remaining slot when the primary is taken`() {
            // odometer slot already filled; a 30 (would-be price-bucket overflow) finds liters/price
            classifier.classify(
                30.0,
                alreadyFilled = setOf(FuelField.LITERS),
                hasTripMeter = false,
            ) shouldBe FuelField.PRICE_PER_LITER
        }
    }
}
