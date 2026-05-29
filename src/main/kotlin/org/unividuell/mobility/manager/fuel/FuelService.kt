package org.unividuell.mobility.manager.fuel

import org.springframework.stereotype.Service

/**
 * Owns the fuel-entry workflow: parse a raw user input, ask the
 * [FuelValueClassifier] which slot it belongs to, update the draft, and
 * persist a completed entry. The controller is responsible only for the
 * HTTP/view layer around this service.
 */
@Service
class FuelService(
    private val classifier: FuelValueClassifier,
    private val repository: FuelEntryRepository,
) {

    sealed interface DraftResult {
        /** Draft still has at least one empty slot. */
        data class Pending(val draft: FuelDraft) : DraftResult

        /** Draft was completed and persisted. */
        data class Completed(val saved: FuelEntry) : DraftResult
    }

    /**
     * Applies one raw input to the draft. The single input field carries four
     * possible targets: a positive number lands in one of the three numeric
     * slots (via [FuelValueClassifier]); anything else (or a number once all
     * numeric slots are full) is matched against the user's vehicles through
     * [resolveVehicle], which returns the vehicle id for a substring match.
     * Completion is checked at the end, so the four parts may arrive in any order.
     */
    fun applyValue(
        draft: FuelDraft,
        rawValue: String,
        resolveVehicle: (String) -> Long?,
    ): DraftResult {
        val updated = run {
            parseValue(rawValue)?.let { parsed ->
                classifier.classify(parsed, draft.filledFields)?.let { return@run draft.with(it, parsed) }
            }
            // not a usable number (or no numeric slot left) → try the vehicle
            resolveVehicle(rawValue.trim())?.let { return@run draft.copy(vehicleId = it) }
            draft
        }

        return if (updated.isComplete) {
            DraftResult.Completed(repository.save(updated.toEntry()))
        } else {
            DraftResult.Pending(updated)
        }
    }

    // German decimal commas are common at the pump; normalise before parsing.
    // Zero and negative values are treated as invalid input.
    private fun parseValue(rawValue: String): Double? =
        rawValue.trim()
            .replace(',', '.')
            .toDoubleOrNull()
            ?.takeIf { it > 0.0 }
}
