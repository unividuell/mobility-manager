package org.unividuell.mobility.manager.fuel

/**
 * Partial fuel-entry state being assembled across multiple keystrokes.
 * Each of the three slots is optional until the user has entered all three;
 * once complete, the draft is turned into a [FuelEntry] for persistence.
 */
data class FuelDraft(
    val liters: Double? = null,
    val pricePerLiter: Double? = null,
    val kilometers: Double? = null,
    val vehicleId: Long? = null,
) {
    val filledFields: Set<FuelField>
        get() = buildSet {
            if (liters != null) add(FuelField.LITERS)
            if (pricePerLiter != null) add(FuelField.PRICE_PER_LITER)
            if (kilometers != null) add(FuelField.KILOMETERS)
        }

    val isComplete: Boolean
        get() = liters != null && pricePerLiter != null && kilometers != null && vehicleId != null

    fun with(field: FuelField, value: Double): FuelDraft = when (field) {
        FuelField.LITERS -> copy(liters = value)
        FuelField.PRICE_PER_LITER -> copy(pricePerLiter = value)
        FuelField.KILOMETERS -> copy(kilometers = value)
    }

    fun toEntry(): FuelEntry = FuelEntry(
        vehicleId = requireNotNull(vehicleId) { "vehicleId must be set" },
        liters = requireNotNull(liters) { "liters must be set" },
        pricePerLiter = requireNotNull(pricePerLiter) { "pricePerLiter must be set" },
        kilometers = requireNotNull(kilometers) { "kilometers must be set" },
    )
}
