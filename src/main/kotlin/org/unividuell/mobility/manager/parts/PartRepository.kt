package org.unividuell.mobility.manager.parts

import org.springframework.data.repository.CrudRepository

interface PartRepository : CrudRepository<Part, Long> {

    /** All parts of a vehicle, newest install first (date, then id as tie-break). */
    fun findAllByVehicleIdOrderByInstalledOnDescIdDesc(vehicleId: Long): List<Part>

    /** Unordered variant used for deleting a vehicle's parts aggregate-aware. */
    fun findAllByVehicleId(vehicleId: Long): List<Part>

    /** Parts pointing at the given part as their successor. */
    fun findAllByReplacedByPartId(partId: Long): List<Part>
}
