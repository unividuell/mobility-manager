package org.unividuell.mobility.manager.fuel

import org.springframework.stereotype.Component
import kotlin.math.abs
import kotlin.math.ln

enum class FuelField(val typicalValue: Double) {
    PRICE_PER_LITER(1.85),
    LITERS(45.0),
    KILOMETERS(500.0),
}

@Component
class FuelValueClassifier {

    /**
     * Classifies a numeric value into one of the still-empty fields.
     *
     * Primary heuristic by magnitude (German gas-station context):
     *   value < 5            → PRICE_PER_LITER  (e.g. 1.859 €/L)
     *   5  ≤ value < 150     → LITERS           (e.g. 45.32 L)
     *   value ≥ 150          → KILOMETERS       (e.g. 520 km)
     *
     * If the primary slot is already filled, falls back to the remaining
     * empty slot whose typical value is closest in log-distance — so values
     * outside the usual range still find a sensible home.
     */
    fun classify(value: Double, alreadyFilled: Set<FuelField>): FuelField? {
        val candidates = FuelField.entries.toSet() - alreadyFilled
        if (candidates.isEmpty()) return null

        val primary = when {
            value < 5.0 -> FuelField.PRICE_PER_LITER
            value < 150.0 -> FuelField.LITERS
            else -> FuelField.KILOMETERS
        }
        if (primary in candidates) return primary

        return candidates.minBy { abs(ln(value / it.typicalValue)) }
    }
}
