package org.unividuell.mobility.manager.fuel

import org.springframework.data.repository.CrudRepository
import org.springframework.data.repository.PagingAndSortingRepository

interface FuelEntryRepository :
    CrudRepository<FuelEntry, Long>,
    PagingAndSortingRepository<FuelEntry, Long> {

    /** All refuelings of a vehicle, newest first (date, then id as tie-break). */
    fun findAllByVehicleIdOrderByDateDescIdDesc(vehicleId: Long): List<FuelEntry>

    /** Every refueling of the given vehicles in one shot, for computing per-vehicle stats. */
    fun findAllByVehicleIdIn(vehicleIds: Collection<Long>): List<FuelEntry>

    /** Removes all refuelings of a vehicle — used when the vehicle itself is deleted. */
    fun deleteAllByVehicleId(vehicleId: Long)
}
