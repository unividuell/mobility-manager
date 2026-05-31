package org.unividuell.mobility.manager.fuel

import org.springframework.stereotype.Component
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

    /**
     * Classifies a numeric value into one of the still-empty fields.
     *
     * The third slot depends on the vehicle: a trip-meter vehicle records a trip
     * distance ([FuelField.KILOMETERS]), a total-only vehicle an absolute odometer
     * reading ([FuelField.ODOMETER]). The magnitude buckets are otherwise the same
     * (German gas-station context):
     *   value < 5            → PRICE_PER_LITER  (e.g. 1.859 €/L)
     *   5  ≤ value < 150     → LITERS           (e.g. 45.32 L)
     *   value ≥ 150          → distance slot    (520 km trip / 123456 km odometer)
     *
     * If the primary slot is already filled, falls back to the remaining empty slot
     * whose typical value is closest in log-distance — so values outside the usual
     * range still find a sensible home.
     */
    fun classify(value: Double, alreadyFilled: Set<FuelField>, hasTripMeter: Boolean): FuelField? {
        val distanceField = if (hasTripMeter) FuelField.KILOMETERS else FuelField.ODOMETER
        val candidates = setOf(FuelField.PRICE_PER_LITER, FuelField.LITERS, distanceField) - alreadyFilled
        if (candidates.isEmpty()) return null

        val primary = when {
            value < 5.0 -> FuelField.PRICE_PER_LITER
            value < 150.0 -> FuelField.LITERS
            else -> distanceField
        }
        if (primary in candidates) return primary

        return candidates.minBy { abs(ln(value / it.typicalValue)) }
    }
}
