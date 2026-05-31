package org.unividuell.mobility.manager.fuel

import kotlin.math.abs

/**
 * How a fuel entry's consumption compares to the previous refueling of the same
 * vehicle. [diff] is signed (current − previous), in L/100 km: positive means
 * consumption rose, negative means it dropped.
 */
data class ConsumptionDelta(
    val previousPer100Km: Double,
    val diff: Double,
) {
    val increased: Boolean get() = diff > 0.0
    val decreased: Boolean get() = diff < 0.0
    val absDiff: Double get() = abs(diff)

    /** Leading sign for display: "+" rose, "−" dropped, "±" unchanged. */
    val sign: String get() = when {
        increased -> "+"
        decreased -> "−"
        else -> "±"
    }
}
