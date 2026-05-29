package org.unividuell.mobility.manager.fuel

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

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

        assertNotNull(saved.id)
        assertTrue(saved.id!! > 0)

        val loaded = repository.findById(saved.id!!).orElseThrow()
        assertEquals(42.5, loaded.liters)
        assertEquals(1.749, loaded.pricePerLiter)
        assertEquals(680.0, loaded.kilometers)
    }

    @Test
    fun `computed consumption per 100km matches formula`() {
        val saved = repository.save(
            FuelEntry(liters = 45.32, pricePerLiter = 1.859, kilometers = 520.0)
        )

        val loaded = repository.findById(saved.id!!).orElseThrow()
        assertEquals(45.32 / 520.0 * 100.0, loaded.consumptionPer100Km, 1e-9)
        assertEquals(45.32 * 1.859, loaded.totalCost, 1e-9)
    }

    @Test
    fun `multiple entries can coexist and be enumerated`() {
        repository.save(FuelEntry(liters = 40.0, pricePerLiter = 1.7, kilometers = 500.0))
        repository.save(FuelEntry(liters = 50.0, pricePerLiter = 1.8, kilometers = 600.0))
        repository.save(FuelEntry(liters = 35.0, pricePerLiter = 1.9, kilometers = 450.0))

        val all = repository.findAll().toList()
        assertEquals(3, all.size)
        assertEquals(
            setOf(40.0, 50.0, 35.0),
            all.map { it.liters }.toSet(),
        )
    }

    @Test
    fun `deleteAll removes everything`() {
        repository.save(FuelEntry(liters = 40.0, pricePerLiter = 1.7, kilometers = 500.0))
        assertEquals(1, repository.count())

        repository.deleteAll()
        assertEquals(0, repository.count())
    }
}
