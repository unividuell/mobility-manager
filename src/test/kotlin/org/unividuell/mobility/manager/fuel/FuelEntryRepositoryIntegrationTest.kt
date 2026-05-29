package org.unividuell.mobility.manager.fuel

import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

@SpringBootTest
class FuelEntryRepositoryIntegrationTest @Autowired constructor(
    private val repository: FuelEntryRepository,
) {

    @BeforeEach
    fun cleanDb() {
        repository.deleteAll()
    }

    @Test
    fun `save assigns an id and persists all fields`() {
        val saved = repository.save(
            FuelEntry(
                liters = 42.5,
                pricePerLiter = 1.749,
                kilometers = 680.0,
            )
        )

        val id = saved.id.shouldNotBeNull()
        id shouldBeGreaterThan 0L

        val loaded = repository.findById(id).orElseThrow()
        loaded.liters shouldBe 42.5
        loaded.pricePerLiter shouldBe 1.749
        loaded.kilometers shouldBe 680.0
    }

    @Test
    fun `computed consumption per 100km matches formula`() {
        val saved = repository.save(
            FuelEntry(liters = 45.32, pricePerLiter = 1.859, kilometers = 520.0)
        )

        val loaded = repository.findById(saved.id!!).orElseThrow()
        loaded.consumptionPer100Km shouldBe (45.32 / 520.0 * 100.0).plusOrMinus(1e-9)
        loaded.totalCost shouldBe (45.32 * 1.859).plusOrMinus(1e-9)
    }

    @Test
    fun `multiple entries can coexist and be enumerated`() {
        repository.save(FuelEntry(liters = 40.0, pricePerLiter = 1.7, kilometers = 500.0))
        repository.save(FuelEntry(liters = 50.0, pricePerLiter = 1.8, kilometers = 600.0))
        repository.save(FuelEntry(liters = 35.0, pricePerLiter = 1.9, kilometers = 450.0))

        val all = repository.findAll().toList()
        all shouldHaveSize 3
        all.map { it.liters } shouldContainExactlyInAnyOrder listOf(40.0, 50.0, 35.0)
    }

    @Test
    fun `deleteAll removes everything`() {
        repository.save(FuelEntry(liters = 40.0, pricePerLiter = 1.7, kilometers = 500.0))
        repository.count() shouldBe 1

        repository.deleteAll()
        repository.count() shouldBe 0
    }
}
