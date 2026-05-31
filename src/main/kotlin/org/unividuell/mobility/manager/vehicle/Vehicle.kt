package org.unividuell.mobility.manager.vehicle

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.MappedCollection
import org.springframework.data.relational.core.mapping.Table

@Table("vehicles")
data class Vehicle(
    @Id val id: Long? = null,
    val name: String,
    val color: String,
    // Whether the vehicle has a resettable trip meter. If so, the driven distance
    // is typed directly per refueling; if not, the user types the absolute odometer
    // reading and the distance is computed from the previous one.
    val hasTripMeter: Boolean = true,
    // Owned join rows: which users may manage this vehicle (M:N to users).
    // Spring Data JDBC cascades inserts/deletes of these rows with the aggregate.
    @MappedCollection(idColumn = "vehicle_id")
    val managers: Set<VehicleManager> = emptySet(),
    // created_at is populated by SQLite's DEFAULT CURRENT_TIMESTAMP; not mapped
    // here to avoid Instant ↔ SQLite typing friction (same as FuelEntry).
)
