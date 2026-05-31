package org.unividuell.mobility.manager.fuel

import kotlin.math.abs

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
 *
 * It also flags consumption outliers (data-entry slips, a missed fill-up, a tank
 * not filled to full) so they can be excluded from the average — see [markOutliers].
 */
object FuelCalculator {

    // Outlier detection only kicks in once there's enough history to judge a
    // "normal" range; below this every refueling counts.
    private const val MIN_POINTS_FOR_OUTLIERS = 5

    // Modified z-score cutoff (Iglewicz–Hoaglin): |z| > 3.5 is the usual threshold.
    private const val MODIFIED_Z_THRESHOLD = 3.5
    private const val MAD_SCALE = 0.6745          // 0.75 quantile of the normal dist
    private const val MEAN_AD_SCALE = 1.2533      // sqrt(pi/2); used when MAD collapses to 0

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
        val points = entries.mapIndexed { i, entry ->
            val distance = distances[i]
            FuelPoint(
                entry = entry,
                distanceKm = distance,
                consumptionPer100Km = distance
                    ?.takeIf { it > 0 }
                    ?.let { entry.liters / it * 100.0 },
            )
        }
        return markOutliers(points)
    }

    /**
     * Flags points whose consumption is a statistical outlier using a robust
     * median + MAD modified z-score — resistant to the very outliers we want to
     * catch, unlike a mean/standard-deviation rule. Needs at least
     * [MIN_POINTS_FOR_OUTLIERS] points with a consumption; otherwise nothing is
     * flagged. When the MAD collapses to 0 (many identical values) it falls back
     * to the mean absolute deviation, and if that is 0 too (all identical) there
     * are no outliers.
     */
    private fun markOutliers(points: List<FuelPoint>): List<FuelPoint> {
        val consumptions = points.mapNotNull { it.consumptionPer100Km }
        if (consumptions.size < MIN_POINTS_FOR_OUTLIERS) return points

        val median = median(consumptions)
        val deviations = consumptions.map { abs(it - median) }
        val mad = median(deviations)
        val scale = when {
            mad > 0.0 -> MAD_SCALE / mad
            else -> {
                val meanAd = deviations.average()
                if (meanAd > 0.0) 1.0 / (MEAN_AD_SCALE * meanAd) else return points
            }
        }

        return points.map { point ->
            val consumption = point.consumptionPer100Km
            if (consumption != null && abs((consumption - median) * scale) > MODIFIED_Z_THRESHOLD) {
                point.copy(isOutlier = true)
            } else {
                point
            }
        }
    }

    private fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2.0
    }
}
