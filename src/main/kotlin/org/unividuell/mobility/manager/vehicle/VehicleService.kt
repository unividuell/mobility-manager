package org.unividuell.mobility.manager.vehicle

import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import org.unividuell.mobility.manager.fuel.FuelEntryRepository

@Service
class VehicleService(
    private val repository: VehicleRepository,
    private val fuelEntries: FuelEntryRepository,
) {

    fun listFor(userId: Long): List<Vehicle> = repository.findAllManagedBy(userId)

    fun create(userId: Long, name: String, color: String, hasTripMeter: Boolean = true): Vehicle =
        repository.save(
            Vehicle(
                name = name.trim(),
                color = color,
                hasTripMeter = hasTripMeter,
                managers = setOf(VehicleManager(userId)),
            ),
        )

    /** Loads a vehicle the user manages, or 404s (never leaks that it exists). */
    fun get(id: Long, userId: Long): Vehicle {
        val vehicle = repository.findById(id).orElse(null)
        if (vehicle == null || vehicle.managers.none { it.userId == userId }) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND)
        }
        return vehicle
    }

    fun update(id: Long, userId: Long, name: String, color: String, hasTripMeter: Boolean): Vehicle {
        // get(...) returns the full aggregate, so the managers set is preserved
        // through the copy/save round-trip.
        val vehicle = get(id, userId)
        return repository.save(vehicle.copy(name = name.trim(), color = color, hasTripMeter = hasTripMeter))
    }

    /**
     * Deletes the vehicle and everything hanging off it. Its refuelings reference
     * the vehicle (FK), so they must go first or SQLite rejects the delete; the
     * manager join rows are owned by the aggregate and cascade with it. Atomic, so
     * a failure can't leave the vehicle gone but its entries orphaned.
     */
    @Transactional
    fun delete(id: Long, userId: Long) {
        val vehicle = get(id, userId)
        fuelEntries.deleteAllByVehicleId(vehicle.id!!)
        repository.deleteById(vehicle.id!!)
    }
}
