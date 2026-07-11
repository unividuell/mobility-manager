package org.unividuell.mobility.manager.parts

import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import org.unividuell.mobility.manager.fuel.FuelService
import org.unividuell.mobility.manager.vehicle.Vehicle
import org.unividuell.mobility.manager.vehicle.VehicleService
import java.time.LocalDate

/**
 * Owns the part-tracking workflow: parts with their maintenance checkpoints and
 * user-owned tags, resolved against the vehicle's derived current km. Every
 * entry point guards ownership through [VehicleService.get] (404, never leaks).
 */
@Service
class PartService(
    private val parts: PartRepository,
    private val tags: TagRepository,
    private val vehicleService: VehicleService,
    private val fuelService: FuelService,
) {

    /** A checkpoint as typed in the form: due offset (km after install) and label. */
    data class CheckpointInput(val offsetKm: Double, val label: String)

    /** Everything the parts page needs, resolved in one go. */
    data class PartsOverview(
        val currentKm: Double?,
        val dueItems: List<DueCalculator.DueItem>,
        val activeParts: List<Part>,
        val retiredParts: List<Part>,
        val tagNamesByPartId: Map<Long, List<String>>,
        /** id -> name of every part of the vehicle, for "ersetzt durch …" links. */
        val partNamesById: Map<Long, String>,
    )

    @Transactional
    fun create(
        userId: Long,
        vehicleId: Long,
        name: String,
        details: String?,
        priceEuro: Long?,
        installedAtKm: Double,
        installedOn: LocalDate,
        tagNames: List<String>,
        checkpoints: List<CheckpointInput>,
    ): Part {
        vehicleService.get(vehicleId, userId) // 404 unless the user owns it
        validate(name, installedAtKm, priceEuro, checkpoints)
        return parts.save(
            Part(
                vehicleId = vehicleId,
                name = name.trim(),
                details = details?.trim()?.ifEmpty { null },
                priceCents = priceEuro?.times(100),
                installedAtKm = installedAtKm,
                installedOn = installedOn,
                checkpoints = checkpoints.map { PartCheckpoint(offsetKm = it.offsetKm, label = it.label.trim()) }.toSet(),
                tags = resolveTags(userId, tagNames),
            ),
        )
    }

    @Transactional
    fun update(
        userId: Long,
        vehicleId: Long,
        partId: Long,
        name: String,
        details: String?,
        priceEuro: Long?,
        installedAtKm: Double,
        installedOn: LocalDate,
        tagNames: List<String>,
        checkpoints: List<CheckpointInput>,
    ): Part {
        val part = get(partId, vehicleId, userId)
        validate(name, installedAtKm, priceEuro, checkpoints)
        // open checkpoints are rebuilt from the form (edit/remove/add in one go);
        // done ones carry history and are kept untouched
        val done = part.checkpoints.filter { !it.open }
        return parts.save(
            part.copy(
                name = name.trim(),
                details = details?.trim()?.ifEmpty { null },
                priceCents = priceEuro?.times(100),
                installedAtKm = installedAtKm,
                installedOn = installedOn,
                checkpoints = (done + checkpoints.map { PartCheckpoint(offsetKm = it.offsetKm, label = it.label.trim()) }).toSet(),
                tags = resolveTags(userId, tagNames),
            ),
        )
    }

    /** Loads a part of an owned vehicle, or 404s (never leaks that it exists). */
    fun get(partId: Long, vehicleId: Long, userId: Long): Part {
        vehicleService.get(vehicleId, userId)
        val part = parts.findById(partId).orElse(null)
        if (part == null || part.vehicleId != vehicleId) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND)
        }
        return part
    }

    fun tagSuggestions(userId: Long): List<Tag> = tags.findAllByUserIdOrderByName(userId)

    /**
     * How many checkpoints of the vehicle are overdue right now — feeds the
     * header badge. No ownership check: callers pass an already-authorised vehicle.
     */
    fun overdueCount(vehicle: Vehicle): Int =
        DueCalculator.openItems(
            parts.findAllByVehicleId(vehicle.id!!),
            fuelService.currentKm(vehicle),
        ).count { it.overdue }

    fun overviewFor(userId: Long, vehicleId: Long): PartsOverview {
        val vehicle = vehicleService.get(vehicleId, userId)
        val all = parts.findAllByVehicleIdOrderByInstalledOnDescIdDesc(vehicleId)
        val currentKm = fuelService.currentKm(vehicle)
        val tagNamesById = tags.findAllByUserIdOrderByName(userId).associate { it.id!! to it.name }
        return PartsOverview(
            currentKm = currentKm,
            dueItems = DueCalculator.openItems(all, currentKm),
            activeParts = all.filter { it.active },
            retiredParts = all.filter { !it.active },
            tagNamesByPartId = all.associate { part ->
                part.id!! to part.tags.mapNotNull { tagNamesById[it.tagId] }.sorted()
            },
            partNamesById = all.associate { it.id!! to it.name },
        )
    }

    /**
     * Confirms an open checkpoint with the (possibly corrected) date and reading
     * from the confirm step. 404s when the checkpoint doesn't belong to the part.
     */
    fun checkOff(userId: Long, vehicleId: Long, partId: Long, checkpointId: Long, doneOn: LocalDate, doneAtKm: Double?) {
        val part = get(partId, vehicleId, userId)
        val target = part.checkpoints.firstOrNull { it.id == checkpointId }
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
        parts.save(
            part.copy(
                checkpoints = part.checkpoints
                    .map { if (it.id == target.id) it.copy(doneOn = doneOn, doneAtKm = doneAtKm) else it }
                    .toSet(),
            ),
        )
    }

    /**
     * Replaces a part in one step: the successor is created and the old part is
     * retired at the successor's install reading/date, linked via replacedByPartId.
     * Atomic — a failure can't leave the successor in but the old part active.
     */
    @Transactional
    fun replace(
        userId: Long,
        vehicleId: Long,
        oldPartId: Long,
        name: String,
        details: String?,
        priceEuro: Long?,
        installedAtKm: Double,
        installedOn: LocalDate,
        tagNames: List<String>,
        checkpoints: List<CheckpointInput>,
    ): Part {
        val old = get(oldPartId, vehicleId, userId)
        val successor = create(userId, vehicleId, name, details, priceEuro, installedAtKm, installedOn, tagNames, checkpoints)
        parts.save(old.copy(retiredOn = installedOn, retiredAtKm = installedAtKm, replacedByPartId = successor.id))
        return successor
    }

    /**
     * Deletes a part (typo correction). Checkpoints and tag joins cascade via the
     * schema; successor references pointing at it are auto-nulled (ON DELETE SET NULL).
     */
    fun delete(userId: Long, vehicleId: Long, partId: Long) {
        val part = get(partId, vehicleId, userId)
        parts.deleteById(part.id!!)
    }

    /** Splits a comma-separated tag input into normalised names: trimmed, lowercase, distinct. */
    private fun resolveTags(userId: Long, tagNames: List<String>): Set<PartTagRef> =
        tagNames.map { name ->
            val existing = tags.findByUserIdAndName(userId, name)
            PartTagRef(tagId = (existing ?: tags.save(Tag(userId = userId, name = name))).id!!)
        }.toSet()

    private fun validate(name: String, installedAtKm: Double, priceEuro: Long?, checkpoints: List<CheckpointInput>) {
        if (name.isBlank() || installedAtKm < 0 || (priceEuro != null && priceEuro < 0) ||
            checkpoints.any { it.offsetKm <= 0 || it.label.isBlank() }
        ) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST)
        }
    }

    companion object {
        /** Splits raw comma-separated input into normalised tag names. */
        fun parseTagNames(raw: String?): List<String> =
            raw.orEmpty().split(',')
                .map { it.trim().lowercase() }
                .filter { it.isNotEmpty() }
                .distinct()
    }
}
