package org.unividuell.mobility.manager.fuel

import org.springframework.stereotype.Service
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoField

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
     * Applies one raw input to the draft. The single input field carries five
     * possible targets: a positive number lands in one of the three numeric
     * slots (via [FuelValueClassifier]); a recognised date fills the date slot;
     * anything else (or a number once all numeric slots are full) is matched
     * against the user's vehicles through [resolveVehicle], which returns the
     * vehicle id for a substring match. Completion is checked at the end, so the
     * parts may arrive in any order.
     */
    fun applyValue(
        draft: FuelDraft,
        rawValue: String,
        resolveVehicle: (String) -> Long?,
    ): DraftResult {
        val trimmed = rawValue.trim()
        val updated = run {
            parseValue(rawValue)?.let { parsed ->
                classifier.classify(parsed, draft.filledFields)?.let { return@run draft.with(it, parsed) }
            }
            parseDate(trimmed)?.let { return@run draft.copy(date = it) }
            // not a usable number/date (or no numeric slot left) → try the vehicle
            resolveVehicle(trimmed)?.let { return@run draft.copy(vehicleId = it) }
            draft
        }

        return if (updated.isComplete) {
            DraftResult.Completed(repository.save(updated.toEntry()))
        } else {
            DraftResult.Pending(updated)
        }
    }

    // Accepts ISO (2026-05-29), German day-first dates (29.5.2026 / 29.05.2026),
    // and a 2-digit year mapped into the last 99 years (29.5.26 → 2026, 20.5.99 → 1999).
    private fun parseDate(raw: String): LocalDate? {
        if (raw.isEmpty()) return null
        for (format in DATE_FORMATS) {
            try {
                return LocalDate.parse(raw, format)
            } catch (_: DateTimeParseException) {
                // try the next format
            }
        }
        return null
    }

    // German decimal commas are common at the pump; normalise before parsing.
    // Zero and negative values are treated as invalid input.
    private fun parseValue(rawValue: String): Double? =
        rawValue.trim()
            .replace(',', '.')
            .toDoubleOrNull()
            ?.takeIf { it > 0.0 }

    private companion object {
        val DATE_FORMATS: List<DateTimeFormatter> = listOf(
            DateTimeFormatter.ISO_LOCAL_DATE,        // 2026-05-29
            DateTimeFormatter.ofPattern("d.M.yyyy"), // 29.5.2026 and 29.05.2026
            // 2-digit year resolved into [today-99y, today]: 26 → 2026, 99 → 1999
            DateTimeFormatterBuilder()
                .appendPattern("d.M.")
                .appendValueReduced(ChronoField.YEAR, 2, 2, LocalDate.now().minusYears(99))
                .toFormatter(),
        )
    }
}
