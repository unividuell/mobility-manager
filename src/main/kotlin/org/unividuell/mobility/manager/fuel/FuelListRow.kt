package org.unividuell.mobility.manager.fuel

/**
 * One refueling as shown in the per-vehicle fuel list: a table row and, via
 * [barHeightPercent] (0–100, relative to the highest consumption in the list),
 * a chart bar. [barHeightPercent] is pre-computed as an Int so the CSS height
 * never carries a locale-formatted decimal.
 */
data class FuelListRow(
    val id: Long,
    val dateDisplay: String,
    val consumption: Double,
    val liters: Double,
    val pricePerLiter: Double,
    val kilometers: Double,
    val totalCost: Double,
    val barHeightPercent: Int,
)
