package org.unividuell.mobility.manager.fuel

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table

@Table("fuel_entries")
data class FuelEntry(
    @Id val id: Long? = null,
    val liters: Double,
    val pricePerLiter: Double,
    val kilometers: Double,
    // created_at is populated by SQLite's DEFAULT CURRENT_TIMESTAMP; we don't
    // map it on the entity yet to avoid Instant ↔ SQLite typing friction.
) {
    val consumptionPer100Km: Double
        get() = liters / kilometers * 100.0

    val totalCost: Double
        get() = liters * pricePerLiter
}
