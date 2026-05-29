package org.unividuell.mobility.manager.vehicle

import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.repository.CrudRepository

interface VehicleRepository : CrudRepository<Vehicle, Long> {

    // Lists the vehicles a user manages. The `managers` collection is NOT
    // hydrated by this custom query — fine, the index only needs id/name/color.
    @Query(
        """
        SELECT v.* FROM vehicles v
        JOIN vehicle_managers m ON m.vehicle_id = v.id
        WHERE m.user_id = :userId
        ORDER BY v.name COLLATE NOCASE
        """,
    )
    fun findAllManagedBy(userId: Long): List<Vehicle>
}
