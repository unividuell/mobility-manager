package org.unividuell.mobility.manager.fuel

import org.springframework.stereotype.Component
import java.math.BigDecimal
import kotlin.math.abs
import kotlin.math.ln

enum class FuelField(val typicalValue: Double) {
    PRICE_PER_LITER(1.85),
    LITERS(45.0),
    KILOMETERS(500.0),    // trip distance, trip-meter vehicles
    ODOMETER(50_000.0),   // absolute reading, total-only vehicles
}

@Component
class FuelValueClassifier {

    /** The slot a value belongs to, and the value to store there (normalised, e.g. cents → euros). */
    data class Classification(val field: FuelField, val value: Double)

    /**
     * Classifies a numeric value into one of the still-empty fields.
     *
     * The third slot depends on the vehicle: a trip-meter vehicle records a trip
     * distance ([FuelField.KILOMETERS]), a total-only vehicle an absolute odometer
     * reading ([FuelField.ODOMETER]). The magnitude buckets are otherwise the same
     * (German gas-station context):
     *   value < 3            → PRICE_PER_LITER  (e.g. 1.859 €/L)
     *   3  ≤ value < 150     → LITERS           (e.g. 45.32 L, or a 4.51 L top-up)
     *   value ≥ 150          → distance slot    (520 km trip / 123456 km odometer)
     *
     * Pumps also show the price in cents (201.9 for 2.019 €/L). While the price slot
     * is empty, a value with exactly one decimal in 100 ≤ value < 300 is read as such
     * and converted to euros — the value's scale is why it arrives as [BigDecimal].
     *
     * If the primary slot is already filled, falls back to the remaining empty slot
     * whose typical value is closest in log-distance — so values outside the usual
     * range still find a sensible home.
     */
    fun classify(value: BigDecimal, alreadyFilled: Set<FuelField>, hasTripMeter: Boolean): Classification? {
        val distanceField = if (hasTripMeter) FuelField.KILOMETERS else FuelField.ODOMETER
        val candidates = setOf(FuelField.PRICE_PER_LITER, FuelField.LITERS, distanceField) - alreadyFilled
        if (candidates.isEmpty()) return null

        if (FuelField.PRICE_PER_LITER in candidates && isCentPrice(value)) {
            return Classification(FuelField.PRICE_PER_LITER, value.movePointLeft(2).toDouble())
        }

        val number = value.toDouble()
        val primary = when {
            // German pump prices stay well below 3 €/L; anything above is a small refill
            number < 3.0 -> FuelField.PRICE_PER_LITER
            number < 150.0 -> FuelField.LITERS
            else -> distanceField
        }
        val field = if (primary in candidates) primary else candidates.minBy { abs(ln(number / it.typicalValue)) }
        return Classification(field, number)
    }

    private fun isCentPrice(value: BigDecimal): Boolean =
        value.scale() == 1 && value >= CENT_PRICE_MIN && value < CENT_PRICE_MAX

    private companion object {
        val CENT_PRICE_MIN = BigDecimal(100)
        val CENT_PRICE_MAX = BigDecimal(300)
    }
}
