package org.unividuell.mobility.manager.fuel

/**
 * Turns raw [FuelEntry] rows into [FuelPoint]s with the driven distance and
 * consumption resolved. This is where the two vehicle modes converge:
 *
 *  - a trip-meter entry carries its own distance ([FuelEntry.kilometers]);
 *  - an odometer entry carries an absolute reading ([FuelEntry.odometer]) and its
 *    distance is this reading minus the previous reading (tank-to-tank). The first
 *    reading has no predecessor, so its distance — and consumption — is null.
 *
 * Distances are derived here rather than stored, so inserting a back-dated entry
 * simply re-runs this and every affected neighbour re-derives correctly.
 */
object FuelCalculator {

    /**
     * Resolves [entries] (given in any order) and returns the points in the SAME
     * order as the input, so callers can keep their newest-first/oldest-first
     * convention. The chronological walk needed for odometer diffs happens
     * internally (by date, then id as tie-break).
     */
    fun resolve(entries: List<FuelEntry>): List<FuelPoint> {
        // Walk chronologically (by date, then id) but key distances by the original
        // index, so the result keeps the input order and we never dereference an id
        // (entries may be unsaved with a null id).
        val chronological = entries.indices.sortedWith(
            compareBy({ entries[it].date }, { entries[it].id ?: Long.MAX_VALUE }),
        )
        val distances = arrayOfNulls<Double>(entries.size)
        var previousOdometer: Double? = null
        for (i in chronological) {
            val entry = entries[i]
            distances[i] = when {
                entry.kilometers != null -> entry.kilometers
                entry.odometer != null -> previousOdometer?.let { (entry.odometer - it).takeIf { d -> d > 0 } }
                else -> null
            }
            // Track the absolute reading regardless of whether a distance resulted,
            // so the next odometer entry measures against the most recent reading.
            if (entry.odometer != null) previousOdometer = entry.odometer
        }
        return entries.mapIndexed { i, entry ->
            val distance = distances[i]
            FuelPoint(
                entry = entry,
                distanceKm = distance,
                consumptionPer100Km = distance
                    ?.takeIf { it > 0 }
                    ?.let { entry.liters / it * 100.0 },
            )
        }
    }
}
