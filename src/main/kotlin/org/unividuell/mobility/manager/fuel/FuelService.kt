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

    fun applyValue(draft: FuelDraft, rawValue: String): DraftResult {
        val parsed = parseValue(rawValue)
            ?: return DraftResult.Pending(draft)

        val target = classifier.classify(parsed, draft.filledFields)
            ?: return DraftResult.Pending(draft)

        val updated = draft.with(target, parsed)

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
