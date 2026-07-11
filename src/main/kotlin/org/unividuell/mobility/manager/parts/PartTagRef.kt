package org.unividuell.mobility.manager.parts

import org.springframework.data.relational.core.mapping.Table

/**
 * Join row linking a [Part] to a user-owned [Tag]. A value object owned by the
 * Part aggregate (same pattern as VehicleManager on Vehicle).
 */
@Table("part_tags")
data class PartTagRef(
    val tagId: Long,
)
