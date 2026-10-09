package org.unividuell.mobility.manager.vehicle

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpStatus
import org.springframework.test.context.ActiveProfiles
import org.springframework.web.server.ResponseStatusException
import org.unividuell.mobility.manager.DatabaseCleaner
import org.unividuell.mobility.manager.fuel.FuelEntry
import org.unividuell.mobility.manager.fuel.FuelEntryRepository
import org.unividuell.mobility.manager.parts.*
import org.unividuell.mobility.manager.user.AppUserService
import java.time.LocalDate

@SpringBootTest
@ActiveProfiles("test")
class VehicleServiceIntegrationTest @Autowired constructor(
    private val service: VehicleService,
    private val repository: VehicleRepository,
    private val fuelEntries: FuelEntryRepository,
    private val parts: PartRepository,
    private val users: AppUserService,
    private val db: DatabaseCleaner,
) {

    // FKs are enforced, so the manager ids must be real users.
    private var userA = 0L
    private var userB = 0L

    @BeforeEach
    fun cleanDb() {
        db.clean()
        userA = users.upsert(provider = "github", subject = "1001", login = "alice", name = "Alice").id!!
        userB = users.upsert(provider = "github", subject = "1002", login = "bob", name = "Bob").id!!
    }

    @Test
    fun `create persists the vehicle and makes the creator its manager`() {
        val saved = service.create(userA, name = "Kombi", color = "#06b6d4")

        saved.id.shouldNotBeNull()
        saved.name shouldBe "Kombi"
        saved.color shouldBe "#06b6d4"

        service.listFor(userA).map { it.name } shouldBe listOf("Kombi")
    }

    @Test
    fun `listFor only returns vehicles the user manages`() {
        service.create(userA, "A-Car", "#06b6d4")
        service.create(userB, "B-Bike", "#f43f5e")

        service.listFor(userA).map { it.name } shouldBe listOf("A-Car")
        service.listFor(userB).map { it.name } shouldBe listOf("B-Bike")
    }

    @Test
    fun `update changes fields and keeps the manager`() {
        val created = service.create(userA, "Old", "#06b6d4", hasTripMeter = true)

        service.update(created.id!!, userA, name = "New", color = "#10b981", hasTripMeter = false)

        val mine = service.listFor(userA)
        mine shouldHaveSize 1
        mine.single().name shouldBe "New"
        mine.single().color shouldBe "#10b981"
        mine.single().hasTripMeter shouldBe false
    }

    @Test
    fun `get, update and delete reject a non-manager with 404`() {
        val created = service.create(userA, "A-Car", "#06b6d4")

        shouldThrow<ResponseStatusException> { service.get(created.id!!, userB) }
            .statusCode shouldBe HttpStatus.NOT_FOUND
        shouldThrow<ResponseStatusException> { service.update(created.id!!, userB, "Hijack", "#000000", hasTripMeter = true) }
            .statusCode shouldBe HttpStatus.NOT_FOUND
        shouldThrow<ResponseStatusException> { service.delete(created.id!!, userB) }
            .statusCode shouldBe HttpStatus.NOT_FOUND

        // unchanged and still present for the real manager
        service.listFor(userA).single().name shouldBe "A-Car"
    }

    @Test
    fun `delete removes the vehicle and its manager rows`() {
        val created = service.create(userA, "Gone", "#06b6d4")

        service.delete(created.id!!, userA)

        repository.count() shouldBe 0
        service.listFor(userA).shouldBeEmpty()
    }

    @Test
    fun `delete also removes the vehicle's refuelings instead of failing the foreign key`() {
        val created = service.create(userA, "Gone", "#06b6d4")
        fuelEntries.save(
            FuelEntry(vehicleId = created.id!!, date = LocalDate.of(2026, 5, 20), liters = 42.5, pricePerLiter = 1.749, kilometers = 680.0),
        )
        fuelEntries.count() shouldBe 1

        service.delete(created.id!!, userA)

        repository.count() shouldBe 0
        fuelEntries.count() shouldBe 0
    }

    @Test
    fun `delete also removes the vehicle's parts with their checkpoints`() {
        val created = service.create(userA, "Gone", "#06b6d4")
        parts.save(
            Part(
                vehicleId = created.id!!, name = "Kupplung", details = null, priceCents = null,
                installedAtKm = 100.0, installedOn = LocalDate.of(2026, 7, 11),
                checkpoints = setOf(PartCheckpoint(offsetKm = 100.0, label = "Kontrolle")),
            ),
        )

        service.delete(created.id!!, userA)

        parts.count() shouldBe 0
    }

    @Test
    fun `update stores the baseline reading, both fields or none`() {
        val created = service.create(userA, "Moped", "#06b6d4", hasTripMeter = true)

        service.update(
            created.id!!, userA, name = "Moped", color = "#06b6d4", hasTripMeter = true,
            baselineKm = 19123.0, baselineOn = LocalDate.of(2026, 7, 11),
        )
        service.get(created.id!!, userA).baselineKm shouldBe 19123.0
        service.get(created.id!!, userA).baselineOn shouldBe LocalDate.of(2026, 7, 11)

        // one half missing -> both cleared (an anchor needs value AND date)
        service.update(created.id!!, userA, "Moped", "#06b6d4", hasTripMeter = true, baselineKm = 20000.0, baselineOn = null)
        service.get(created.id!!, userA).baselineKm shouldBe null
        service.get(created.id!!, userA).baselineOn shouldBe null
    }
}
