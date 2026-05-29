package org.unividuell.mobility.manager.vehicle

import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException

@Service
class VehicleService(
    private val repository: VehicleRepository,
) {

    fun listFor(userId: Long): List<Vehicle> = repository.findAllManagedBy(userId)

    /**
     * Resolves a user-typed query to one of the user's vehicles: an exact
     * name match (case-insensitive) wins; otherwise a *unique* substring match;
     * otherwise null (blank, no match, or ambiguous).
     */
    fun resolve(userId: Long, query: String): Vehicle? {
        val needle = query.trim()
        if (needle.isEmpty()) return null
        val mine = listFor(userId)
        return mine.firstOrNull { it.name.equals(needle, ignoreCase = true) }
            ?: mine.filter { it.name.contains(needle, ignoreCase = true) }.singleOrNull()
    }

    fun create(userId: Long, name: String, color: String): Vehicle =
        repository.save(
            Vehicle(
                name = name.trim(),
                color = color,
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

    fun update(id: Long, userId: Long, name: String, color: String): Vehicle {
        // get(...) returns the full aggregate, so the managers set is preserved
        // through the copy/save round-trip.
        val vehicle = get(id, userId)
        return repository.save(vehicle.copy(name = name.trim(), color = color))
    }

    fun delete(id: Long, userId: Long) {
        val vehicle = get(id, userId)
        repository.deleteById(vehicle.id!!)
    }
}
