package org.unividuell.mobility.manager.fuel

import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.Optional

/**
 * Unit test for [FuelService]. Plain JUnit 5 (grouped with [Nested] classes),
 * kotest is used only for assertions. The classifier is a pure-logic value
 * object, so it's used as-is; the repository is mocked with mockk because it
 * carries the I/O side effect.
 */
class FuelServiceTest {

    private fun newService(repository: FuelEntryRepository): FuelService =
        FuelService(FuelValueClassifier(), repository)

    @Nested
    inner class InvalidInput {

        @Test
        fun `returns the unchanged draft for a non-numeric, non-date value`() {
            val repository = mockk<FuelEntryRepository>()
            val service = newService(repository)
            val draft = FuelDraft(liters = 42.5)

            val result = service.applyValue(draft, "abc")

            result shouldBe FuelService.DraftResult.Pending(draft)
            verify(exactly = 0) { repository.save(any()) }
            confirmVerified(repository)
        }

        @Test
        fun `returns the unchanged draft for zero`() {
            val service = newService(mockk())

            val result = service.applyValue(FuelDraft(), "0")

            result shouldBe FuelService.DraftResult.Pending(FuelDraft())
        }

        @Test
        fun `returns the unchanged draft for a negative value`() {
            val service = newService(mockk())

            val result = service.applyValue(FuelDraft(), "-1.5")

            result shouldBe FuelService.DraftResult.Pending(FuelDraft())
        }

        @Test
        fun `returns the unchanged draft for blank input`() {
            val service = newService(mockk())

            val result = service.applyValue(FuelDraft(), "   ")

            result shouldBe FuelService.DraftResult.Pending(FuelDraft())
        }
    }

    @Nested
    inner class ClassificationByMagnitude {

        @Test
        fun `routes a value below 5 to PRICE_PER_LITER`() {
            val service = newService(mockk(relaxed = true))

            val result = service.applyValue(FuelDraft(), "1.859")

            result shouldBe FuelService.DraftResult.Pending(FuelDraft(pricePerLiter = 1.859))
        }

        @Test
        fun `routes a value between 5 and 150 to LITERS`() {
            val service = newService(mockk(relaxed = true))

            val result = service.applyValue(FuelDraft(), "45.32")

            result shouldBe FuelService.DraftResult.Pending(FuelDraft(liters = 45.32))
        }

        @Test
        fun `routes a value of 150 or more to KILOMETERS`() {
            val service = newService(mockk(relaxed = true))

            val result = service.applyValue(FuelDraft(), "520")

            result shouldBe FuelService.DraftResult.Pending(FuelDraft(kilometers = 520.0))
        }
    }

    @Nested
    inner class LocaleHandling {

        @Test
        fun `accepts a german comma decimal separator`() {
            val service = newService(mockk(relaxed = true))

            val result = service.applyValue(FuelDraft(), "1,859")

            result shouldBe FuelService.DraftResult.Pending(FuelDraft(pricePerLiter = 1.859))
        }

        @Test
        fun `trims surrounding whitespace`() {
            val service = newService(mockk(relaxed = true))

            val result = service.applyValue(FuelDraft(), "  45,5  ")

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
            val result = service.applyValue(FuelDraft(liters = 45.0), "30")

            result shouldBe FuelService.DraftResult.Pending(
                FuelDraft(liters = 45.0, pricePerLiter = 30.0),
            )
        }
    }

    @Nested
    inner class DateMatching {

        @Test
        fun `matches an ISO date and fills the date slot`() {
            val service = newService(mockk(relaxed = true))

            val result = service.applyValue(FuelDraft(), "2026-05-20")

            result shouldBe FuelService.DraftResult.Pending(FuelDraft(date = LocalDate.of(2026, 5, 20)))
        }

        @Test
        fun `matches a german day-first date and fills the date slot`() {
            val service = newService(mockk(relaxed = true))

            val result = service.applyValue(FuelDraft(), "20.05.2026")

            result shouldBe FuelService.DraftResult.Pending(FuelDraft(date = LocalDate.of(2026, 5, 20)))
        }

        @Test
        fun `accepts a two-digit year, mapping it into the last 99 years`() {
            val service = newService(mockk(relaxed = true))

            // run-year is 2026: 26 → 2026, 99 → 1999
            service.applyValue(FuelDraft(), "20.5.26") shouldBe
                FuelService.DraftResult.Pending(FuelDraft(date = LocalDate.of(2026, 5, 20)))
            service.applyValue(FuelDraft(), "20.5.99") shouldBe
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

            val result = service.applyValue(draft, "20.05.2026")

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

            val result = service.applyValue(draft, "680")

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

            val result = service.applyValue(full, "99")

            // Not complete (no vehicle) and the stray value finds no home.
            result.shouldBeInstanceOf<FuelService.DraftResult.Pending>()
            result.draft shouldBe full
            verify(exactly = 0) { repository.save(any()) }
        }
    }

    @Nested
    inner class DeletingAnEntry {

        private fun entry(id: Long, vehicleId: Long) = FuelEntry(
            id = id,
            vehicleId = vehicleId,
            date = LocalDate.of(2026, 1, 1),
            liters = 42.5,
            pricePerLiter = 1.749,
            kilometers = 680.0,
        )

        @Test
        fun `deletes the entry when it belongs to one of the caller's vehicles`() {
            val repository = mockk<FuelEntryRepository>(relaxed = true)
            val service = newService(repository)
            every { repository.findById(7L) } returns Optional.of(entry(id = 7L, vehicleId = 3L))

            service.delete(7L, ownedVehicleIds = setOf(3L, 4L))

            verify(exactly = 1) { repository.deleteById(7L) }
        }

        @Test
        fun `does not delete an entry that belongs to another user's vehicle`() {
            val repository = mockk<FuelEntryRepository>()
            val service = newService(repository)
            every { repository.findById(7L) } returns Optional.of(entry(id = 7L, vehicleId = 99L))

            service.delete(7L, ownedVehicleIds = setOf(3L, 4L))

            verify(exactly = 0) { repository.deleteById(any()) }
        }

        @Test
        fun `does nothing when the entry no longer exists`() {
            val repository = mockk<FuelEntryRepository>()
            val service = newService(repository)
            every { repository.findById(7L) } returns Optional.empty()

            service.delete(7L, ownedVehicleIds = setOf(3L))

            verify(exactly = 0) { repository.deleteById(any()) }
        }
    }

    @Nested
    inner class SavedSummary {

        // consumption = liters / kilometers * 100
        private fun entry(id: Long, liters: Double, kilometers: Double) = FuelEntry(
            id = id,
            vehicleId = 3L,
            date = LocalDate.of(2026, 1, 1),
            liters = liters,
            pricePerLiter = 1.7,
            kilometers = kilometers,
        )

        // newest first, as the repository returns it
        private fun timelineReturns(repository: FuelEntryRepository, vararg entries: FuelEntry) {
            every { repository.findAllByVehicleIdOrderByDateDescIdDesc(3L) } returns entries.toList()
        }

        @Test
        fun `delta is null when there is no previous entry`() {
            val repository = mockk<FuelEntryRepository>()
            val service = newService(repository)
            val only = entry(id = 2L, liters = 45.0, kilometers = 600.0)
            timelineReturns(repository, only)

            val summary = service.summarize(only)
            summary.point.consumptionPer100Km!! shouldBe (7.5 plusOrMinus 1e-9)
            summary.delta.shouldBeNull()
        }

        @Test
        fun `reports a signed rise against the previous entry`() {
            val repository = mockk<FuelEntryRepository>()
            val service = newService(repository)
            val current = entry(id = 2L, liters = 45.0, kilometers = 600.0)  // 7.5
            val previous = entry(id = 1L, liters = 40.0, kilometers = 800.0) // 5.0
            timelineReturns(repository, current, previous)

            val delta = service.summarize(current).delta!!
            delta.previousPer100Km shouldBe (5.0 plusOrMinus 1e-9)
            delta.diff shouldBe (2.5 plusOrMinus 1e-9)
            delta.increased shouldBe true
            delta.sign shouldBe "+"
        }

        @Test
        fun `reports a signed drop against the previous entry`() {
            val repository = mockk<FuelEntryRepository>()
            val service = newService(repository)
            val current = entry(id = 2L, liters = 40.0, kilometers = 800.0)  // 5.0
            val previous = entry(id = 1L, liters = 45.0, kilometers = 600.0) // 7.5
            timelineReturns(repository, current, previous)

            val delta = service.summarize(current).delta!!
            delta.diff shouldBe ((-2.5) plusOrMinus 1e-9)
            delta.decreased shouldBe true
            delta.sign shouldBe "−"
        }

        @Test
        fun `odometer entry derives distance from the previous reading, first has none`() {
            val repository = mockk<FuelEntryRepository>()
            val service = newService(repository)
            // total-only vehicle: two readings 800 km apart; the 45 L cover that 800 km.
            val first = FuelEntry(id = 1L, vehicleId = 3L, date = LocalDate.of(2026, 1, 1), liters = 40.0, pricePerLiter = 1.7, odometer = 50_000.0)
            val second = FuelEntry(id = 2L, vehicleId = 3L, date = LocalDate.of(2026, 1, 20), liters = 45.0, pricePerLiter = 1.7, odometer = 50_800.0)
            timelineReturns(repository, second, first)

            val firstSummary = service.summarize(first)
            firstSummary.point.distanceKm.shouldBeNull()
            firstSummary.point.consumptionPer100Km.shouldBeNull()
            firstSummary.delta.shouldBeNull()

            val secondSummary = service.summarize(second)
            secondSummary.point.distanceKm!! shouldBe (800.0 plusOrMinus 1e-9)
            secondSummary.point.consumptionPer100Km!! shouldBe (45.0 / 800.0 * 100.0 plusOrMinus 1e-9)
            secondSummary.delta.shouldBeNull() // previous reading has no consumption to compare against
        }
    }

    @Nested
    inner class PerVehicleStats {

        private fun entry(vehicleId: Long, liters: Double, kilometers: Double) = FuelEntry(
            id = null,
            vehicleId = vehicleId,
            date = LocalDate.of(2026, 1, 1),
            liters = liters,
            pricePerLiter = 1.7,
            kilometers = kilometers,
        )

        @Test
        fun `averages consumption over total distance, not per refueling`() {
            val repository = mockk<FuelEntryRepository>()
            val service = newService(repository)
            // 40L/800km = 5.0 and 50L/500km = 10.0; the big short tank must pull the
            // average up, so a period average (90L/1300km ≈ 6.92) — not (5+10)/2 = 7.5.
            every { repository.findAllByVehicleIdIn(listOf(3L)) } returns listOf(
                entry(vehicleId = 3L, liters = 40.0, kilometers = 800.0),
                entry(vehicleId = 3L, liters = 50.0, kilometers = 500.0),
            )

            val stats = service.statsByVehicle(listOf(3L)).getValue(3L)

            stats.entryCount shouldBe 2
            stats.totalKilometers shouldBe (1300.0 plusOrMinus 1e-9)
            stats.averageConsumptionPer100Km!! shouldBe (6.923 plusOrMinus 1e-3)
        }

        @Test
        fun `reports empty stats with a null average for a vehicle with no refuelings`() {
            val repository = mockk<FuelEntryRepository>()
            val service = newService(repository)
            every { repository.findAllByVehicleIdIn(listOf(7L)) } returns emptyList()

            val stats = service.statsByVehicle(listOf(7L)).getValue(7L)

            stats.entryCount shouldBe 0
            stats.totalKilometers shouldBe (0.0 plusOrMinus 1e-9)
            stats.averageConsumptionPer100Km.shouldBeNull()
        }

        @Test
        fun `keys stats by vehicle and includes ids that have no entries`() {
            val repository = mockk<FuelEntryRepository>()
            val service = newService(repository)
            every { repository.findAllByVehicleIdIn(listOf(1L, 2L)) } returns listOf(
                entry(vehicleId = 1L, liters = 30.0, kilometers = 600.0),
            )

            val stats = service.statsByVehicle(listOf(1L, 2L))

            stats.keys shouldBe setOf(1L, 2L)
            stats.getValue(1L).averageConsumptionPer100Km!! shouldBe (5.0 plusOrMinus 1e-9)
            stats.getValue(2L).averageConsumptionPer100Km.shouldBeNull()
        }

        @Test
        fun `does not touch the repository when there are no vehicles`() {
            val repository = mockk<FuelEntryRepository>()
            val service = newService(repository)

            service.statsByVehicle(emptyList()) shouldBe emptyMap()

            verify(exactly = 0) { repository.findAllByVehicleIdIn(any()) }
            confirmVerified(repository)
        }
    }
}
