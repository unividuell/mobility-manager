package org.unividuell.mobility.manager.fuel

import org.unividuell.mobility.manager.vehicle.Vehicle
import java.time.LocalDate

/**
 * A known absolute odometer reading as of the END of [on] — the vehicle's baseline.
 * Fuel entries dated on/before [on] are already part of it; the first odometer entry
 * after it measures its distance against [km]. This is what lets a vehicle switch
 * from trip-meter to total-only: the reading taken at the switch bridges the gap
 * to the first absolute entry.
 */
data class OdometerAnchor(val km: Double, val on: LocalDate)

/** The vehicle's baseline as an [OdometerAnchor], or null unless both parts are set. */
val Vehicle.odometerAnchor: OdometerAnchor?
    get() {
        val km = baselineKm ?: return null
        val on = baselineOn ?: return null
        return OdometerAnchor(km, on)
    }
