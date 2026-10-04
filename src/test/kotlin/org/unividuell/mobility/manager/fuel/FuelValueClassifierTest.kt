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

    private fun field(raw: String, filled: Set<FuelField> = emptySet(), hasTripMeter: Boolean = true): FuelField? =
        classifier.classify(raw.toBigDecimal(), filled, hasTripMeter)?.field

    @Nested
    inner class TripMeterVehicle {

        @Test
        fun `a large value is the trip distance`() {
            field("520") shouldBe FuelField.KILOMETERS
        }

        @Test
        fun `price and liters keep their buckets`() {
            field("1.859") shouldBe FuelField.PRICE_PER_LITER
            field("45") shouldBe FuelField.LITERS
        }

        @Test
        fun `the price bucket ends at 3`() {
            field("2.999") shouldBe FuelField.PRICE_PER_LITER
            field("3") shouldBe FuelField.LITERS
            field("4.51") shouldBe FuelField.LITERS
        }

        @Test
        fun `the odometer slot is never offered`() {
            // even a typical odometer-sized value lands in the trip slot for these vehicles
            field("123456") shouldBe FuelField.KILOMETERS
        }
    }

    @Nested
    inner class TotalOnlyVehicle {

        @Test
        fun `a large value is the odometer reading`() {
            field("123456", hasTripMeter = false) shouldBe FuelField.ODOMETER
            // the boundary value that would be a trip distance also maps to the odometer slot
            field("520", hasTripMeter = false) shouldBe FuelField.ODOMETER
        }

        @Test
        fun `price and liters keep their buckets`() {
            field("1.859", hasTripMeter = false) shouldBe FuelField.PRICE_PER_LITER
            field("45", hasTripMeter = false) shouldBe FuelField.LITERS
        }

        @Test
        fun `the trip-distance slot is never offered`() {
            field(
                "123456",
                filled = setOf(FuelField.PRICE_PER_LITER, FuelField.LITERS),
                hasTripMeter = false,
            ) shouldBe FuelField.ODOMETER
        }

        @Test
        fun `falls back to the remaining slot when the primary is taken`() {
            // odometer slot already filled; a 30 (would-be price-bucket overflow) finds liters/price
            field("30", filled = setOf(FuelField.LITERS), hasTripMeter = false) shouldBe FuelField.PRICE_PER_LITER
        }
    }

    @Nested
    inner class CentPrice {

        @Test
        fun `a one-decimal value between 100 and 300 is a price in cents, converted to euros`() {
            classifier.classify("201.9".toBigDecimal(), emptySet(), hasTripMeter = true) shouldBe
                FuelValueClassifier.Classification(FuelField.PRICE_PER_LITER, 2.019)
            classifier.classify("201.9".toBigDecimal(), emptySet(), hasTripMeter = false) shouldBe
                FuelValueClassifier.Classification(FuelField.PRICE_PER_LITER, 2.019)
        }

        @Test
        fun `the cent range is 100 inclusive to 300 exclusive`() {
            field("100.0") shouldBe FuelField.PRICE_PER_LITER
            field("299.9") shouldBe FuelField.PRICE_PER_LITER
            field("300.0") shouldBe FuelField.KILOMETERS
            field("99.9") shouldBe FuelField.LITERS
        }

        @Test
        fun `once the price is set, the same value is a distance again`() {
            field("201.9", filled = setOf(FuelField.PRICE_PER_LITER)) shouldBe FuelField.KILOMETERS
            field("201.9", filled = setOf(FuelField.PRICE_PER_LITER), hasTripMeter = false) shouldBe
                FuelField.ODOMETER
        }

        @Test
        fun `only exactly one decimal marks a cent price`() {
            field("201") shouldBe FuelField.KILOMETERS
            field("201.95") shouldBe FuelField.KILOMETERS
        }

        @Test
        fun `a euro price is passed through unchanged`() {
            classifier.classify("2.019".toBigDecimal(), emptySet(), hasTripMeter = true) shouldBe
                FuelValueClassifier.Classification(FuelField.PRICE_PER_LITER, 2.019)
        }
    }
}
