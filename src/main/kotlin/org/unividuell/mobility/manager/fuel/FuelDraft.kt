package org.unividuell.mobility.manager.fuel

import java.time.LocalDate

/**
 * Partial fuel-entry state being assembled across multiple keystrokes.
 * Each slot is optional until the user has entered all of them; once complete,
 * the draft is turned into a [FuelEntry] for persistence. The date defaults to
 * today (set by the controller) but can be overridden by typing one.
 *
 * The distance slot is mode-dependent: [hasTripMeter] vehicles fill [kilometers]
 * (a trip distance), total-only vehicles fill [odometer] (an absolute reading).
 * The flag comes from the selected vehicle and steers classification and saving.
 */
data class FuelDraft(
    val liters: Double? = null,
    val pricePerLiter: Double? = null,
    val kilometers: Double? = null,
    val odometer: Double? = null,
    val vehicleId: Long? = null,
    val date: LocalDate? = null,
    val hasTripMeter: Boolean = true,
) {
    /** The slot value the user types for the driven distance, by vehicle mode. */
    val distance: Double?
        get() = if (hasTripMeter) kilometers else odometer

    val filledFields: Set<FuelField>
        get() = buildSet {
            if (liters != null) add(FuelField.LITERS)
            if (pricePerLiter != null) add(FuelField.PRICE_PER_LITER)
            if (hasTripMeter && kilometers != null) add(FuelField.KILOMETERS)
            if (!hasTripMeter && odometer != null) add(FuelField.ODOMETER)
        }

    val isComplete: Boolean
        get() = liters != null && pricePerLiter != null && distance != null &&
            vehicleId != null && date != null

    fun with(field: FuelField, value: Double): FuelDraft = when (field) {
        FuelField.LITERS -> copy(liters = value)
        FuelField.PRICE_PER_LITER -> copy(pricePerLiter = value)
        FuelField.KILOMETERS -> copy(kilometers = value)
        FuelField.ODOMETER -> copy(odometer = value)
    }

    fun toEntry(): FuelEntry = FuelEntry(
        vehicleId = requireNotNull(vehicleId) { "vehicleId must be set" },
        date = requireNotNull(date) { "date must be set" },
        liters = requireNotNull(liters) { "liters must be set" },
        pricePerLiter = requireNotNull(pricePerLiter) { "pricePerLiter must be set" },
        // store only the field that matches the vehicle's mode; the other stays null
        kilometers = if (hasTripMeter) requireNotNull(kilometers) { "kilometers must be set" } else null,
        odometer = if (hasTripMeter) null else requireNotNull(odometer) { "odometer must be set" },
    )
}
