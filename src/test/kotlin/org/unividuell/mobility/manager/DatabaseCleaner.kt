package org.unividuell.mobility.manager

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

/**
 * Empties the domain tables for integration tests, children before parents.
 *
 * Foreign keys are enforced, so every test class used to repeat this delete
 * order by hand — and each new table referencing vehicles or users silently
 * broke the copies that didn't know it, depending on test execution order.
 * Keeping the order in one place (guarded by [DatabaseCleanerIntegrationTest])
 * removes that trap.
 */
@Component
class DatabaseCleaner(private val jdbc: JdbcTemplate) {

    fun clean() {
        TABLES.forEach { jdbc.update("DELETE FROM $it") }
    }

    companion object {
        /** Every domain table, in a delete order that satisfies the foreign keys. */
        val TABLES = listOf(
            "part_tags",
            "part_checkpoints",
            "parts",
            "tags",
            "fuel_entries",
            "vehicle_managers",
            "vehicles",
            "users",
        )
    }
}
