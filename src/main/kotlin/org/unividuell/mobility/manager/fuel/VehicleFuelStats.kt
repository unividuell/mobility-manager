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
 */
data class VehicleFuelStats(
    val entryCount: Int,
    val totalKilometers: Double,
    val averageConsumptionPer100Km: Double?,
) {
    companion object {
        fun from(points: List<FuelPoint>): VehicleFuelStats {
            val measured = points.filter { it.distanceKm != null }
            val totalKilometers = measured.sumOf { it.distanceKm!! }
            val totalLiters = measured.sumOf { it.liters }
            return VehicleFuelStats(
                entryCount = points.size,
                totalKilometers = totalKilometers,
                averageConsumptionPer100Km =
                    if (totalKilometers > 0) totalLiters / totalKilometers * 100.0 else null,
            )
        }
    }
}
