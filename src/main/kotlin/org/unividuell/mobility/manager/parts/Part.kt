package org.unividuell.mobility.manager.parts

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.MappedCollection
import org.springframework.data.relational.core.mapping.Table
import java.time.LocalDate

/**
 * A tracked part installed in a vehicle. Active while [retiredOn] is null;
 * retiring records the removal reading and, when replaced, the successor part.
 * Checkpoints and tag references are owned by this aggregate — Spring Data JDBC
 * rewrites them on every save (their ids are not stable across saves).
 */
@Table("parts")
data class Part(
    @Id val id: Long? = null,
    val vehicleId: Long,
    val name: String,
    val details: String? = null,
    val priceCents: Long? = null,
    val installedAtKm: Double,
    val installedOn: LocalDate,
    val retiredAtKm: Double? = null,
    val retiredOn: LocalDate? = null,
    val replacedByPartId: Long? = null,
    @MappedCollection(idColumn = "part_id")
    val checkpoints: Set<PartCheckpoint> = emptySet(),
    @MappedCollection(idColumn = "part_id")
    val tags: Set<PartTagRef> = emptySet(),
    // created_at is populated by SQLite's DEFAULT CURRENT_TIMESTAMP (not mapped).
) {
    val active: Boolean get() = retiredOn == null

    // money is stored as integer cents; entered and shown as whole euros
    val priceEuro: Long? get() = priceCents?.div(100)
}
