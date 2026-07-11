package org.unividuell.mobility.manager.parts

/**
 * Resolves the open maintenance checkpoints of a vehicle's parts against its
 * current total km. Only active parts and open checkpoints count. A checkpoint
 * is overdue once the due reading is reached exactly. With an unknown current
 * km (null) no verdict is made — items are listed by their due reading instead.
 */
object DueCalculator {

    data class DueItem(
        val part: Part,
        val checkpoint: PartCheckpoint,
        val dueAtKm: Double,
        /** negative or zero = overdue by that many km; null = unknown current km */
        val remainingKm: Double?,
        val overdue: Boolean,
    )

    fun openItems(parts: List<Part>, currentKm: Double?): List<DueItem> = parts
        .filter { it.active }
        .flatMap { part ->
            part.checkpoints.filter { it.open }.map { checkpoint ->
                val dueAtKm = part.installedAtKm + checkpoint.offsetKm
                val remainingKm = currentKm?.let { dueAtKm - it }
                DueItem(
                    part = part,
                    checkpoint = checkpoint,
                    dueAtKm = dueAtKm,
                    remainingKm = remainingKm,
                    overdue = remainingKm != null && remainingKm <= 0,
                )
            }
        }
        // overdue (most negative remaining) first, then upcoming by remaining km;
        // with unknown current km every remaining is null -> due reading decides
        .sortedWith(compareBy({ it.remainingKm ?: Double.MAX_VALUE }, { it.dueAtKm }))
}
