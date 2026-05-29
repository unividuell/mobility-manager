package org.unividuell.mobility.manager.fuel

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify

/**
 * Unit test for [FuelService]. The classifier is a pure-logic value object,
 * so it's used as-is; the repository is mocked with mockk because it carries
 * the I/O side effect.
 */
class FuelServiceTest : DescribeSpec({

    fun newService(repository: FuelEntryRepository): FuelService =
        FuelService(FuelValueClassifier(), repository)

    describe("applyValue") {

        describe("invalid input") {

            it("returns the unchanged draft for a non-numeric value") {
                val repository = mockk<FuelEntryRepository>()
                val service = newService(repository)
                val draft = FuelDraft(liters = 42.5)

                val result = service.applyValue(draft, "abc")

                result shouldBe FuelService.DraftResult.Pending(draft)
                verify(exactly = 0) { repository.save(any()) }
                confirmVerified(repository)
            }

            it("returns the unchanged draft for zero") {
                val repository = mockk<FuelEntryRepository>()
                val service = newService(repository)

                val result = service.applyValue(FuelDraft(), "0")

                result shouldBe FuelService.DraftResult.Pending(FuelDraft())
            }

            it("returns the unchanged draft for a negative value") {
                val repository = mockk<FuelEntryRepository>()
                val service = newService(repository)

                val result = service.applyValue(FuelDraft(), "-1.5")

                result shouldBe FuelService.DraftResult.Pending(FuelDraft())
            }

            it("returns the unchanged draft for blank input") {
                val repository = mockk<FuelEntryRepository>()
                val service = newService(repository)

                val result = service.applyValue(FuelDraft(), "   ")

                result shouldBe FuelService.DraftResult.Pending(FuelDraft())
            }
        }

        describe("classification by magnitude") {

            it("routes a value below 5 to PRICE_PER_LITER") {
                val service = newService(mockk(relaxed = true))

                val result = service.applyValue(FuelDraft(), "1.859")

                result shouldBe FuelService.DraftResult.Pending(
                    FuelDraft(pricePerLiter = 1.859),
                )
            }

            it("routes a value between 5 and 150 to LITERS") {
                val service = newService(mockk(relaxed = true))

                val result = service.applyValue(FuelDraft(), "45.32")

                result shouldBe FuelService.DraftResult.Pending(
                    FuelDraft(liters = 45.32),
                )
            }

            it("routes a value of 150 or more to KILOMETERS") {
                val service = newService(mockk(relaxed = true))

                val result = service.applyValue(FuelDraft(), "520")

                result shouldBe FuelService.DraftResult.Pending(
                    FuelDraft(kilometers = 520.0),
                )
            }
        }

        describe("locale handling") {

            it("accepts a german comma decimal separator") {
                val service = newService(mockk(relaxed = true))

                val result = service.applyValue(FuelDraft(), "1,859")

                result shouldBe FuelService.DraftResult.Pending(
                    FuelDraft(pricePerLiter = 1.859),
                )
            }

            it("trims surrounding whitespace") {
                val service = newService(mockk(relaxed = true))

                val result = service.applyValue(FuelDraft(), "  45,5  ")

                result shouldBe FuelService.DraftResult.Pending(
                    FuelDraft(liters = 45.5),
                )
            }
        }

        describe("fallback when primary slot is filled") {

            it("falls back to PRICE_PER_LITER when LITERS is taken and value is closer to 1.85 in log-space") {
                val service = newService(mockk(relaxed = true))

                // Primary classification for 30 would be LITERS (5 ≤ x < 150),
                // but liters is already filled. Among remaining slots,
                // PRICE_PER_LITER (typical 1.85) is closer than KILOMETERS
                // (typical 500) in log-distance.
                val result = service.applyValue(FuelDraft(liters = 45.0), "30")

                result shouldBe FuelService.DraftResult.Pending(
                    FuelDraft(liters = 45.0, pricePerLiter = 30.0),
                )
            }
        }

        describe("completing a draft") {

            it("persists the entry and returns Completed when the third value lands") {
                val repository = mockk<FuelEntryRepository>()
                val service = newService(repository)
                val draft = FuelDraft(liters = 42.5, pricePerLiter = 1.749)
                val persisted = FuelEntry(
                    id = 7L,
                    liters = 42.5,
                    pricePerLiter = 1.749,
                    kilometers = 680.0,
                )
                every { repository.save(any()) } returns persisted

                val result = service.applyValue(draft, "680")

                result shouldBe FuelService.DraftResult.Completed(persisted)
                verify(exactly = 1) {
                    repository.save(
                        FuelEntry(
                            liters = 42.5,
                            pricePerLiter = 1.749,
                            kilometers = 680.0,
                        ),
                    )
                }
                confirmVerified(repository)
            }

            it("returns Pending and does NOT persist if the new value cannot be classified") {
                val repository = mockk<FuelEntryRepository>()
                val service = newService(repository)
                // All three slots are filled, so classify() returns null.
                val full = FuelDraft(liters = 45.0, pricePerLiter = 1.8, kilometers = 500.0)

                val result = service.applyValue(full, "99")

                // Already-complete drafts aren't re-saved by a stray extra value.
                result.shouldBeInstanceOf<FuelService.DraftResult.Pending>()
                result.draft shouldBe full
                verify(exactly = 0) { repository.save(any()) }
            }
        }
    }
})
