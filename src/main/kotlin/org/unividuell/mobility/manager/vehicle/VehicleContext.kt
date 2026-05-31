package org.unividuell.mobility.manager.vehicle

import jakarta.servlet.http.HttpSession
import org.springframework.stereotype.Component

/**
 * The user's globally selected vehicle, kept in the HTTP session — and thus, via
 * Spring Session JDBC, surviving a restart. Every fuel entry is recorded against
 * this vehicle; it is shown in the header and changed from the vehicle overview.
 */
@Component
class VehicleContext(
    private val vehicleService: VehicleService,
) {

    /**
     * The vehicle the user is currently working with, or null when none is chosen.
     * A sole vehicle is auto-selected; a stored id that no longer belongs to the
     * user (deleted, or never theirs) is discarded. The session is kept in sync so
     * the next read is stable.
     */
    fun current(session: HttpSession, userId: Long): Vehicle? {
        val vehicles = vehicleService.listFor(userId)
        val stored = session.getAttribute(KEY) as? Long
        val chosen = vehicles.firstOrNull { it.id == stored } ?: vehicles.singleOrNull()
        if (chosen?.id != stored) {
            if (chosen == null) session.removeAttribute(KEY) else session.setAttribute(KEY, chosen.id)
        }
        return chosen
    }

    /** Persists the chosen vehicle as the active context. Caller verifies ownership. */
    fun select(session: HttpSession, vehicleId: Long) {
        session.setAttribute(KEY, vehicleId)
    }

    private companion object {
        const val KEY = "selectedVehicleId"
    }
}
