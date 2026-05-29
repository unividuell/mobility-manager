package org.unividuell.mobility.manager.vehicle

import org.springframework.data.relational.core.mapping.Table

/**
 * Join row linking a [Vehicle] to a managing user (`users.id`). A value object
 * owned by the Vehicle aggregate — no own identity beyond the (vehicle, user) pair.
 */
@Table("vehicle_managers")
data class VehicleManager(
    val userId: Long,
)
