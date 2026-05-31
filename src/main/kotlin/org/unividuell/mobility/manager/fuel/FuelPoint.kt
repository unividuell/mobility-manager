package org.unividuell.mobility.manager.fuel

import java.time.LocalDate

/**
 * A refueling with its driven distance and consumption resolved against the
 * vehicle's history (see [FuelCalculator]).
 *
 * For a trip-meter entry the distance is exactly what the user typed; for an
 * odometer entry it is this reading minus the previous one — a derived value, so
 * a back-dated entry inserted later automatically re-derives its neighbours'
 * distances without any stored figure needing to change.
 *
 * [distanceKm] and [consumptionPer100Km] are null when no distance can be
 * established: the very first odometer reading has no predecessor to measure
 * against (tank-to-tank: that entry's litres covered an unknown distance).
 */
data class FuelPoint(
    val entry: FuelEntry,
    val distanceKm: Double?,
    val consumptionPer100Km: Double?,
) {
    val id: Long? get() = entry.id
    val date: LocalDate get() = entry.date
    val liters: Double get() = entry.liters
    val pricePerLiter: Double get() = entry.pricePerLiter
    val totalCost: Double get() = entry.totalCost
}
