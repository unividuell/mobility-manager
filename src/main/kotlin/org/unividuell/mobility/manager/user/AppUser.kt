package org.unividuell.mobility.manager.user

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.util.UUID

@Table("users")
data class AppUser(
    @Id val id: Long? = null,
    // The id the auth lib's session principal carries (AuthPrincipal.id).
    val accountId: UUID,
    // Who the sign-in door says this is: provider ("github", "test") and its
    // stable id, as text. Login and display name are refreshed at every sign-in.
    val provider: String,
    val subject: String,
    val login: String,
    val displayName: String,
    // created_at is populated by SQLite's DEFAULT CURRENT_TIMESTAMP; not mapped
    // here to avoid Instant ↔ SQLite typing friction (same as FuelEntry).
)
