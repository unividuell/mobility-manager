package org.unividuell.mobility.manager.fuel

/**
 * Aggregated fuel figures for a single vehicle, shown on the vehicle overview.
 *
 * [averageConsumptionPer100Km] is the period average (total liters over total
 * distance), which is more faithful than averaging the per-refueling rates — a
 * big tank weighs more than a small top-up. It is null until there is any tracked
 * distance to divide by. Only points with a known distance contribute, so an
 * odometer vehicle's first reading (no measurable distance) is excluded from both
 * the total distance and the litres that divide into it.
 *
 * Consumption outliers are excluded from the average (and counted in
 * [outlierCount]) so a single bad refueling doesn't skew it — but they stay in
 * [totalKilometers] and [entryCount], which are factual totals of what was driven.
 */
data class VehicleFuelStats(
    val entryCount: Int,
    val totalKilometers: Double,
    val averageConsumptionPer100Km: Double?,
    val outlierCount: Int,
) {
    companion object {
        fun from(points: List<FuelPoint>): VehicleFuelStats {
            val measured = points.filter { it.distanceKm != null }
            // the average is over non-outliers; the distance total stays factual
            val forAverage = measured.filter { !it.isOutlier }
            val averageKilometers = forAverage.sumOf { it.distanceKm!! }
            val averageLiters = forAverage.sumOf { it.liters }
            return VehicleFuelStats(
                entryCount = points.size,
                totalKilometers = measured.sumOf { it.distanceKm!! },
                averageConsumptionPer100Km =
                    if (averageKilometers > 0) averageLiters / averageKilometers * 100.0 else null,
                outlierCount = measured.count { it.isOutlier },
            )
        }
    }
}
