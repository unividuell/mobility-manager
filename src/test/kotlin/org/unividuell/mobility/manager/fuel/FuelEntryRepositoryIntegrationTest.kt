package org.unividuell.mobility.manager.fuel

import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.unividuell.mobility.manager.vehicle.Vehicle
import org.unividuell.mobility.manager.vehicle.VehicleRepository
import java.time.LocalDate

@SpringBootTest
@ActiveProfiles("test")
class FuelEntryRepositoryIntegrationTest @Autowired constructor(
    private val repository: FuelEntryRepository,
    private val vehicles: VehicleRepository,
) {

    // FKs are enforced, so fuel entries must reference a real vehicle.
    // A vehicle with no managers needs no user, keeping this test self-contained.
    private var vehicleId = 0L

    @BeforeEach
    fun cleanDb() {
        repository.deleteAll()
        vehicles.deleteAll()
        vehicleId = vehicles.save(Vehicle(name = "Kombi", color = "#06b6d4")).id!!
    }

    @Test
    fun `save assigns an id and persists all fields`() {
        val saved = repository.save(
            FuelEntry(
                vehicleId = vehicleId,
                date = LocalDate.of(2026, 5, 20),
                liters = 42.5,
                pricePerLiter = 1.749,
                kilometers = 680.0,
            )
        )

        val id = saved.id.shouldNotBeNull()
        id shouldBeGreaterThan 0L

        val loaded = repository.findById(id).orElseThrow()
        loaded.vehicleId shouldBe vehicleId
        loaded.date shouldBe LocalDate.of(2026, 5, 20)   // LocalDate round-trips through the TEXT column
        loaded.liters shouldBe 42.5
        loaded.pricePerLiter shouldBe 1.749
        loaded.kilometers shouldBe 680.0
    }

    @Test
    fun `computed consumption per 100km matches formula`() {
        val saved = repository.save(
            FuelEntry(vehicleId = vehicleId, date = LocalDate.of(2026, 5, 20), liters = 45.32, pricePerLiter = 1.859, kilometers = 520.0)
        )

        val loaded = repository.findById(saved.id!!).orElseThrow()
        loaded.consumptionPer100Km shouldBe (45.32 / 520.0 * 100.0).plusOrMinus(1e-9)
        loaded.totalCost shouldBe (45.32 * 1.859).plusOrMinus(1e-9)
    }

    @Test
    fun `multiple entries can coexist and be enumerated`() {
        repository.save(FuelEntry(vehicleId = vehicleId, date = LocalDate.of(2026, 5, 20), liters = 40.0, pricePerLiter = 1.7, kilometers = 500.0))
        repository.save(FuelEntry(vehicleId = vehicleId, date = LocalDate.of(2026, 5, 20), liters = 50.0, pricePerLiter = 1.8, kilometers = 600.0))
        repository.save(FuelEntry(vehicleId = vehicleId, date = LocalDate.of(2026, 5, 20), liters = 35.0, pricePerLiter = 1.9, kilometers = 450.0))

        val all = repository.findAll().toList()
        all shouldHaveSize 3
        all.map { it.liters } shouldContainExactlyInAnyOrder listOf(40.0, 50.0, 35.0)
    }

    @Test
    fun `deleteAll removes everything`() {
        repository.save(FuelEntry(vehicleId = vehicleId, date = LocalDate.of(2026, 5, 20), liters = 40.0, pricePerLiter = 1.7, kilometers = 500.0))
        repository.count() shouldBe 1

        repository.deleteAll()
        repository.count() shouldBe 0
    }

    @Test
    fun `findPrevious returns the chronologically preceding entry for the vehicle`() {
        val older = repository.save(entry(date = LocalDate.of(2026, 5, 1)))
        val newer = repository.save(entry(date = LocalDate.of(2026, 5, 20)))

        repository.findPrevious(vehicleId, newer.date, newer.id!!)?.id shouldBe older.id
    }

    @Test
    fun `findPrevious returns null for the first entry`() {
        val first = repository.save(entry(date = LocalDate.of(2026, 5, 1)))

        repository.findPrevious(vehicleId, first.date, first.id!!).shouldBeNull()
    }

    @Test
    fun `findPrevious breaks a same-date tie by id`() {
        val a = repository.save(entry(date = LocalDate.of(2026, 5, 20)))
        val b = repository.save(entry(date = LocalDate.of(2026, 5, 20)))

        repository.findPrevious(vehicleId, b.date, b.id!!)?.id shouldBe a.id
    }

    @Test
    fun `findPrevious ignores entries of other vehicles`() {
        val otherVehicle = vehicles.save(Vehicle(name = "Roadster", color = "#f43f5e")).id!!
        repository.save(entry(vehicleId = otherVehicle, date = LocalDate.of(2026, 5, 10)))
        val mine = repository.save(entry(date = LocalDate.of(2026, 5, 20)))

        repository.findPrevious(vehicleId, mine.date, mine.id!!).shouldBeNull()
    }

    private fun entry(vehicleId: Long = this.vehicleId, date: LocalDate) = FuelEntry(
        vehicleId = vehicleId,
        date = date,
        liters = 40.0,
        pricePerLiter = 1.7,
        kilometers = 500.0,
    )
}
