package org.unividuell.mobility.manager.fuel

/**
 * Aggregated fuel figures for a single vehicle, shown on the vehicle overview.
 *
 * [averageConsumptionPer100Km] is the period average (total liters over total
 * distance), which is more faithful than averaging the per-refueling rates — a
 * big tank weighs more than a small top-up. It is null until there is any tracked
 * distance to divide by.
 */
data class VehicleFuelStats(
    val entryCount: Int,
    val totalKilometers: Double,
    val averageConsumptionPer100Km: Double?,
) {
    companion object {
        fun from(entries: List<FuelEntry>): VehicleFuelStats {
            val totalKilometers = entries.sumOf { it.kilometers }
            val totalLiters = entries.sumOf { it.liters }
            return VehicleFuelStats(
                entryCount = entries.size,
                totalKilometers = totalKilometers,
                averageConsumptionPer100Km =
                    if (totalKilometers > 0) totalLiters / totalKilometers * 100.0 else null,
            )
        }
    }
}
