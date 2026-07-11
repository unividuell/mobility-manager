package org.unividuell.mobility.manager.parts

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table

/**
 * A user-owned label for parts, reusable across all of the user's vehicles.
 * Names are stored trimmed + lowercase, unique per user.
 */
@Table("tags")
data class Tag(
    @Id val id: Long? = null,
    val userId: Long,
    val name: String,
)
