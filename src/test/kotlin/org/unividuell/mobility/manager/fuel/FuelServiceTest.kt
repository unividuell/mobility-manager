package org.unividuell.mobility.manager.fuel

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.LocalDate

/**
 * Unit test for [FuelService]. Plain JUnit 5 (grouped with [Nested] classes),
 * kotest is used only for assertions. The classifier is a pure-logic value
 * object, so it's used as-is; the repository is mocked with mockk because it
 * carries the I/O side effect. The vehicle resolver is passed in as a lambda.
 */
class FuelServiceTest {

    private fun newService(repository: FuelEntryRepository): FuelService =
        FuelService(FuelValueClassifier(), repository)

    // most cases don't involve a vehicle: resolver matches nothing
    private val noVehicle: (String) -> Long? = { null }

    @Nested
    inner class InvalidInput {

        @Test
        fun `returns the unchanged draft for a non-numeric value that matches no vehicle`() {
            val repository = mockk<FuelEntryRepository>()
            val service = newService(repository)
            val draft = FuelDraft(liters = 42.5)

            val result = service.applyValue(draft, "abc", noVehicle)

            result shouldBe FuelService.DraftResult.Pending(draft)
            verify(exactly = 0) { repository.save(any()) }
            confirmVerified(repository)
        }

        @Test
        fun `returns the unchanged draft for zero`() {
            val service = newService(mockk())

            val result = service.applyValue(FuelDraft(), "0", noVehicle)

            result shouldBe FuelService.DraftResult.Pending(FuelDraft())
        }

        @Test
        fun `returns the unchanged draft for a negative value`() {
            val service = newService(mockk())

            val result = service.applyValue(FuelDraft(), "-1.5", noVehicle)

            result shouldBe FuelService.DraftResult.Pending(FuelDraft())
        }

        @Test
        fun `returns the unchanged draft for blank input`() {
            val service = newService(mockk())

            val result = service.applyValue(FuelDraft(), "   ", noVehicle)

            result shouldBe FuelService.DraftResult.Pending(FuelDraft())
        }
    }

    @Nested
    inner class ClassificationByMagnitude {

        @Test
        fun `routes a value below 5 to PRICE_PER_LITER`() {
            val service = newService(mockk(relaxed = true))

            val result = service.applyValue(FuelDraft(), "1.859", noVehicle)

            result shouldBe FuelService.DraftResult.Pending(FuelDraft(pricePerLiter = 1.859))
        }

        @Test
        fun `routes a value between 5 and 150 to LITERS`() {
            val service = newService(mockk(relaxed = true))

            val result = service.applyValue(FuelDraft(), "45.32", noVehicle)

            result shouldBe FuelService.DraftResult.Pending(FuelDraft(liters = 45.32))
        }

        @Test
        fun `routes a value of 150 or more to KILOMETERS`() {
            val service = newService(mockk(relaxed = true))

            val result = service.applyValue(FuelDraft(), "520", noVehicle)

            result shouldBe FuelService.DraftResult.Pending(FuelDraft(kilometers = 520.0))
        }
    }

    @Nested
    inner class LocaleHandling {

        @Test
        fun `accepts a german comma decimal separator`() {
            val service = newService(mockk(relaxed = true))

            val result = service.applyValue(FuelDraft(), "1,859", noVehicle)

            result shouldBe FuelService.DraftResult.Pending(FuelDraft(pricePerLiter = 1.859))
        }

        @Test
        fun `trims surrounding whitespace`() {
            val service = newService(mockk(relaxed = true))

            val result = service.applyValue(FuelDraft(), "  45,5  ", noVehicle)

            result shouldBe FuelService.DraftResult.Pending(FuelDraft(liters = 45.5))
        }
    }

    @Nested
    inner class FallbackWhenPrimarySlotFilled {

        @Test
        fun `falls back to PRICE_PER_LITER when LITERS is taken and value is closer to 1_85 in log-space`() {
            val service = newService(mockk(relaxed = true))

            // Primary classification for 30 would be LITERS (5 ≤ x < 150), but
            // liters is already filled. Among the remaining slots PRICE_PER_LITER
            // (typical 1.85) is closer than KILOMETERS (typical 500) in log-distance.
            val result = service.applyValue(FuelDraft(liters = 45.0), "30", noVehicle)

            result shouldBe FuelService.DraftResult.Pending(
                FuelDraft(liters = 45.0, pricePerLiter = 30.0),
            )
        }
    }

    @Nested
    inner class VehicleMatching {

        @Test
        fun `matches a non-numeric value to a vehicle and fills the vehicle slot`() {
            val service = newService(mockk(relaxed = true))

            val result = service.applyValue(FuelDraft(), "Kombi") { 3L }

            result shouldBe FuelService.DraftResult.Pending(FuelDraft(vehicleId = 3L))
        }

        @Test
        fun `completes and persists when the vehicle is the last missing piece`() {
            val repository = mockk<FuelEntryRepository>()
            val service = newService(repository)
            val date = LocalDate.of(2026, 1, 1)
            val draft = FuelDraft(liters = 42.5, pricePerLiter = 1.749, kilometers = 680.0, date = date)
            val persisted = FuelEntry(
                id = 9L,
                vehicleId = 3L,
                date = date,
                liters = 42.5,
                pricePerLiter = 1.749,
                kilometers = 680.0,
            )
            every { repository.save(any()) } returns persisted

            val result = service.applyValue(draft, "Kombi") { 3L }

            result shouldBe FuelService.DraftResult.Completed(persisted)
            verify(exactly = 1) {
                repository.save(
                    FuelEntry(vehicleId = 3L, date = date, liters = 42.5, pricePerLiter = 1.749, kilometers = 680.0),
                )
            }
            confirmVerified(repository)
        }
    }

    @Nested
    inner class DateMatching {

        @Test
        fun `matches an ISO date and fills the date slot`() {
            val service = newService(mockk(relaxed = true))

            val result = service.applyValue(FuelDraft(), "2026-05-20", noVehicle)

            result shouldBe FuelService.DraftResult.Pending(FuelDraft(date = LocalDate.of(2026, 5, 20)))
        }

        @Test
        fun `matches a german day-first date and fills the date slot`() {
            val service = newService(mockk(relaxed = true))

            val result = service.applyValue(FuelDraft(), "20.05.2026", noVehicle)

            result shouldBe FuelService.DraftResult.Pending(FuelDraft(date = LocalDate.of(2026, 5, 20)))
        }

        @Test
        fun `accepts a two-digit year, mapping it into the last 99 years`() {
            val service = newService(mockk(relaxed = true))

            // run-year is 2026: 26 → 2026, 99 → 1999
            service.applyValue(FuelDraft(), "20.5.26", noVehicle) shouldBe
                FuelService.DraftResult.Pending(FuelDraft(date = LocalDate.of(2026, 5, 20)))
            service.applyValue(FuelDraft(), "20.5.99", noVehicle) shouldBe
                FuelService.DraftResult.Pending(FuelDraft(date = LocalDate.of(1999, 5, 20)))
        }

        @Test
        fun `completes and persists when the date is the last missing piece`() {
            val repository = mockk<FuelEntryRepository>()
            val service = newService(repository)
            val draft = FuelDraft(liters = 42.5, pricePerLiter = 1.749, kilometers = 680.0, vehicleId = 3L)
            val persisted = FuelEntry(
                id = 11L,
                vehicleId = 3L,
                date = LocalDate.of(2026, 5, 20),
                liters = 42.5,
                pricePerLiter = 1.749,
                kilometers = 680.0,
            )
            every { repository.save(any()) } returns persisted

            val result = service.applyValue(draft, "20.05.2026", noVehicle)

            result shouldBe FuelService.DraftResult.Completed(persisted)
            verify(exactly = 1) {
                repository.save(
                    FuelEntry(
                        vehicleId = 3L,
                        date = LocalDate.of(2026, 5, 20),
                        liters = 42.5,
                        pricePerLiter = 1.749,
                        kilometers = 680.0,
                    ),
                )
            }
            confirmVerified(repository)
        }
    }

    @Nested
    inner class CompletingADraft {

        @Test
        fun `persists the entry and returns Completed when the last number lands`() {
            val repository = mockk<FuelEntryRepository>()
            val service = newService(repository)
            val date = LocalDate.of(2026, 1, 1)
            val draft = FuelDraft(liters = 42.5, pricePerLiter = 1.749, vehicleId = 3L, date = date)
            val persisted = FuelEntry(
                id = 7L,
                vehicleId = 3L,
                date = date,
                liters = 42.5,
                pricePerLiter = 1.749,
                kilometers = 680.0,
            )
            every { repository.save(any()) } returns persisted

            val result = service.applyValue(draft, "680", noVehicle)

            result shouldBe FuelService.DraftResult.Completed(persisted)
            verify(exactly = 1) {
                repository.save(
                    FuelEntry(vehicleId = 3L, date = date, liters = 42.5, pricePerLiter = 1.749, kilometers = 680.0),
                )
            }
            confirmVerified(repository)
        }

        @Test
        fun `returns Pending and does NOT persist if the new value cannot be classified`() {
            val repository = mockk<FuelEntryRepository>()
            val service = newService(repository)
            // All three numeric slots are filled and no vehicle matches "99".
            val full = FuelDraft(liters = 45.0, pricePerLiter = 1.8, kilometers = 500.0)

            val result = service.applyValue(full, "99", noVehicle)

            // Not complete (no vehicle) and the stray value finds no home.
            result.shouldBeInstanceOf<FuelService.DraftResult.Pending>()
            result.draft shouldBe full
            verify(exactly = 0) { repository.save(any()) }
        }
    }
}
