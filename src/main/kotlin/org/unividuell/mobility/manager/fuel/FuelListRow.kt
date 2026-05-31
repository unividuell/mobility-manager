package org.unividuell.mobility.manager.fuel

/**
 * One refueling as shown in the per-vehicle fuel list: a table row and, via
 * [barHeightPercent] (0–100, relative to the highest consumption in the list),
 * a chart bar. [barHeightPercent] is pre-computed as an Int so the CSS height
 * never carries a locale-formatted decimal.
 *
 * [consumption] and [kilometers] are nullable: an odometer vehicle's first
 * refueling has no measurable distance to compute either from (its bar is empty).
 *
 * [outlier] marks a consumption outlier — rendered as a faded, striped bar and
 * excluded from the vehicle's average (but still listed).
 */
data class FuelListRow(
    val id: Long,
    val dateDisplay: String,
    val consumption: Double?,
    val liters: Double,
    val pricePerLiter: Double,
    val kilometers: Double?,
    val totalCost: Double,
    val barHeightPercent: Int,
    val outlier: Boolean,
)
