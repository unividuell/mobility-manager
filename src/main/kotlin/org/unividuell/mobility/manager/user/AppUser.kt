package org.unividuell.mobility.manager.user

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table

@Table("users")
data class AppUser(
    @Id val id: Long? = null,
    // The user's identity is GitHub's stable numeric id; login and display
    // name are mirrored from GitHub and refreshed on every login.
    val githubId: Long,
    val login: String,
    val displayName: String,
    // created_at is populated by SQLite's DEFAULT CURRENT_TIMESTAMP; not mapped
    // here to avoid Instant ↔ SQLite typing friction (same as FuelEntry).
)
