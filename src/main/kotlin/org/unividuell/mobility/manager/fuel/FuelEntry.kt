package org.unividuell.mobility.manager.fuel

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.time.LocalDate

@Table("fuel_entries")
data class FuelEntry(
    @Id val id: Long? = null,
    val vehicleId: Long,
    val date: LocalDate,
    val liters: Double,
    val pricePerLiter: Double,
    // Exactly one of these is set, depending on the vehicle: [kilometers] is the
    // trip distance for a trip-meter vehicle; [odometer] is the absolute reading
    // for a total-only vehicle (its driven distance is computed from the previous
    // reading, see FuelCalculator — so it is intentionally not stored here).
    val kilometers: Double? = null,
    val odometer: Double? = null,
    // created_at is populated by SQLite's DEFAULT CURRENT_TIMESTAMP; we don't
    // map it on the entity yet to avoid Instant ↔ SQLite typing friction.
) {
    val totalCost: Double
        get() = liters * pricePerLiter
}
