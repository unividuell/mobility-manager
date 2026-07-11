package org.unividuell.mobility.manager.parts

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.time.LocalDate

/**
 * A one-shot maintenance checkpoint on a [Part], due at the part's
 * installed-at km plus [offsetKm]. Checking it off records when and at which
 * reading it was done; both stay null while open.
 */
@Table("part_checkpoints")
data class PartCheckpoint(
    @Id val id: Long? = null,
    val offsetKm: Double,
    val label: String,
    val doneOn: LocalDate? = null,
    val doneAtKm: Double? = null,
) {
    val open: Boolean get() = doneOn == null
}
