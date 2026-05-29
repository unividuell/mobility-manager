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
 * the I/O side effect. The vehicle resolver is passed in as a lambda.
 */
class FuelServiceTest : DescribeSpec({

    fun newService(repository: FuelEntryRepository): FuelService =
        FuelService(FuelValueClassifier(), repository)

    // most cases don't involve a vehicle: resolver matches nothing
    val noVehicle: (String) -> Long? = { null }

    describe("applyValue") {

        describe("invalid input") {

            it("returns the unchanged draft for a non-numeric value that matches no vehicle") {
                val repository = mockk<FuelEntryRepository>()
                val service = newService(repository)
                val draft = FuelDraft(liters = 42.5)

                val result = service.applyValue(draft, "abc", noVehicle)

                result shouldBe FuelService.DraftResult.Pending(draft)
                verify(exactly = 0) { repository.save(any()) }
                confirmVerified(repository)
            }

            it("returns the unchanged draft for zero") {
                val repository = mockk<FuelEntryRepository>()
                val service = newService(repository)

                val result = service.applyValue(FuelDraft(), "0", noVehicle)

                result shouldBe FuelService.DraftResult.Pending(FuelDraft())
            }

            it("returns the unchanged draft for a negative value") {
                val repository = mockk<FuelEntryRepository>()
                val service = newService(repository)

                val result = service.applyValue(FuelDraft(), "-1.5", noVehicle)

                result shouldBe FuelService.DraftResult.Pending(FuelDraft())
            }

            it("returns the unchanged draft for blank input") {
                val repository = mockk<FuelEntryRepository>()
                val service = newService(repository)

                val result = service.applyValue(FuelDraft(), "   ", noVehicle)

                result shouldBe FuelService.DraftResult.Pending(FuelDraft())
            }
        }

        describe("classification by magnitude") {

            it("routes a value below 5 to PRICE_PER_LITER") {
                val service = newService(mockk(relaxed = true))

                val result = service.applyValue(FuelDraft(), "1.859", noVehicle)

                result shouldBe FuelService.DraftResult.Pending(
                    FuelDraft(pricePerLiter = 1.859),
                )
            }

            it("routes a value between 5 and 150 to LITERS") {
                val service = newService(mockk(relaxed = true))

                val result = service.applyValue(FuelDraft(), "45.32", noVehicle)

                result shouldBe FuelService.DraftResult.Pending(
                    FuelDraft(liters = 45.32),
                )
            }

            it("routes a value of 150 or more to KILOMETERS") {
                val service = newService(mockk(relaxed = true))

                val result = service.applyValue(FuelDraft(), "520", noVehicle)

                result shouldBe FuelService.DraftResult.Pending(
                    FuelDraft(kilometers = 520.0),
                )
            }
        }

        describe("locale handling") {

            it("accepts a german comma decimal separator") {
                val service = newService(mockk(relaxed = true))

                val result = service.applyValue(FuelDraft(), "1,859", noVehicle)

                result shouldBe FuelService.DraftResult.Pending(
                    FuelDraft(pricePerLiter = 1.859),
                )
            }

            it("trims surrounding whitespace") {
                val service = newService(mockk(relaxed = true))

                val result = service.applyValue(FuelDraft(), "  45,5  ", noVehicle)

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
                val result = service.applyValue(FuelDraft(liters = 45.0), "30", noVehicle)

                result shouldBe FuelService.DraftResult.Pending(
                    FuelDraft(liters = 45.0, pricePerLiter = 30.0),
                )
            }
        }

        describe("vehicle matching (the 4th option in the same input)") {

            it("matches a non-numeric value to a vehicle and fills the vehicle slot") {
                val service = newService(mockk(relaxed = true))

                val result = service.applyValue(FuelDraft(), "Kombi") { 3L }

                result shouldBe FuelService.DraftResult.Pending(FuelDraft(vehicleId = 3L))
            }

            it("completes and persists when the vehicle is the last missing piece") {
                val repository = mockk<FuelEntryRepository>()
                val service = newService(repository)
                val draft = FuelDraft(liters = 42.5, pricePerLiter = 1.749, kilometers = 680.0)
                val persisted = FuelEntry(
                    id = 9L,
                    vehicleId = 3L,
                    liters = 42.5,
                    pricePerLiter = 1.749,
                    kilometers = 680.0,
                )
                every { repository.save(any()) } returns persisted

                val result = service.applyValue(draft, "Kombi") { 3L }

                result shouldBe FuelService.DraftResult.Completed(persisted)
                verify(exactly = 1) {
                    repository.save(
                        FuelEntry(
                            vehicleId = 3L,
                            liters = 42.5,
                            pricePerLiter = 1.749,
                            kilometers = 680.0,
                        ),
                    )
                }
                confirmVerified(repository)
            }
        }

        describe("completing a draft") {

            it("persists the entry and returns Completed when the last number lands") {
                val repository = mockk<FuelEntryRepository>()
                val service = newService(repository)
                val draft = FuelDraft(liters = 42.5, pricePerLiter = 1.749, vehicleId = 3L)
                val persisted = FuelEntry(
                    id = 7L,
                    vehicleId = 3L,
                    liters = 42.5,
                    pricePerLiter = 1.749,
                    kilometers = 680.0,
                )
                every { repository.save(any()) } returns persisted

                val result = service.applyValue(draft, "680", noVehicle)

                result shouldBe FuelService.DraftResult.Completed(persisted)
                verify(exactly = 1) {
                    repository.save(
                        FuelEntry(
                            vehicleId = 3L,
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
})
