package org.unividuell.mobility.manager.fuel

import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.repository.CrudRepository
import org.springframework.data.repository.PagingAndSortingRepository
import java.time.LocalDate

interface FuelEntryRepository :
    CrudRepository<FuelEntry, Long>,
    PagingAndSortingRepository<FuelEntry, Long> {

    // The refueling that chronologically precedes a given one for the same
    // vehicle: the latest entry strictly before it (date, then id as tie-break).
    // ISO date strings sort chronologically, so a plain TEXT comparison works.
    @Query(
        """
        SELECT * FROM fuel_entries
        WHERE vehicle_id = :vehicleId
          AND (date < :date OR (date = :date AND id < :id))
        ORDER BY date DESC, id DESC
        LIMIT 1
        """,
    )
    fun findPrevious(vehicleId: Long, date: LocalDate, id: Long): FuelEntry?

    /** All refuelings of a vehicle, newest first (date, then id as tie-break). */
    fun findAllByVehicleIdOrderByDateDescIdDesc(vehicleId: Long): List<FuelEntry>

    /** Every refueling of the given vehicles in one shot, for computing per-vehicle stats. */
    fun findAllByVehicleIdIn(vehicleIds: Collection<Long>): List<FuelEntry>
}
