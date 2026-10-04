package org.unividuell.mobility.manager.fuel

import org.unividuell.mobility.manager.vehicle.Vehicle

/**
 * Derives a vehicle's current total km from its fuel data — or null when it is
 * unknowable:
 *
 *  - total-only vehicle: the highest recorded odometer reading, or its baseline
 *    if that is higher (a vehicle switched from trip-meter has no absolute
 *    entry since the switch yet);
 *  - trip-meter vehicle: the baseline reading plus every trip distance recorded
 *    strictly AFTER the baseline date (trips on/before that day are already part
 *    of the reading). No baseline -> unknown.
 */
object CurrentKmCalculator {

    fun currentKm(vehicle: Vehicle, entries: List<FuelEntry>): Double? =
        if (vehicle.hasTripMeter) {
            val baselineOn = vehicle.baselineOn
            vehicle.baselineKm?.let { base ->
                if (baselineOn == null) return null
                base + entries.filter { it.date > baselineOn }.sumOf { it.kilometers ?: 0.0 }
            }
        } else {
            (entries.mapNotNull { it.odometer } + listOfNotNull(vehicle.odometerAnchor?.km)).maxOrNull()
        }
}
