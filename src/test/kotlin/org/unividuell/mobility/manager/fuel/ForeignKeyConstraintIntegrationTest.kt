package org.unividuell.mobility.manager.fuel

import io.kotest.assertions.throwables.shouldThrow
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.test.context.ActiveProfiles
import java.time.LocalDate

/**
 * Guards that SQLite actually enforces referential integrity. fuel_entries.vehicle_id
 * has a FK to vehicles(id); inserting an entry for a non-existent vehicle must be
 * rejected (SQLite enforces FKs only when `PRAGMA foreign_keys = ON` per connection).
 */
@SpringBootTest
@ActiveProfiles("test")
class ForeignKeyConstraintIntegrationTest @Autowired constructor(
    private val fuelEntries: FuelEntryRepository,
) {

    @Test
    fun `inserting a fuel entry for a non-existent vehicle violates the foreign key`() {
        shouldThrow<DataIntegrityViolationException> {
            fuelEntries.save(
                FuelEntry(
                    vehicleId = 999_999L, // no such vehicle
                    date = LocalDate.of(2026, 5, 20),
                    liters = 42.5,
                    pricePerLiter = 1.749,
                    kilometers = 680.0,
                ),
            )
        }
    }
}
