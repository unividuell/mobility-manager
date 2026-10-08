package org.unividuell.mobility.manager.parts

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldHaveSize
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
import org.unividuell.mobility.manager.user.AppUserService
import org.unividuell.mobility.manager.vehicle.VehicleService
import java.time.LocalDate

@SpringBootTest
@ActiveProfiles("test")
class PartServiceIntegrationTest @Autowired constructor(
    private val service: PartService,
    private val parts: PartRepository,
    private val tags: TagRepository,
    private val vehicleService: VehicleService,
    private val fuelEntries: FuelEntryRepository,
    private val users: AppUserService,
    private val db: DatabaseCleaner,
) {

    private var userA = 0L
    private var userB = 0L
    private var mopedId = 0L

    @BeforeEach
    fun cleanDb() {
        db.clean()
        userA = users.upsert(provider = "github", subject = "1001", login = "alice", name = "Alice").id!!
        userB = users.upsert(provider = "github", subject = "1002", login = "bob", name = "Bob").id!!
        mopedId = vehicleService.create(userA, "Moped", "#06b6d4").id!!
    }

    private fun createClutch(): Part = service.create(
        userA, mopedId, name = "Kupplung Sachs", details = "verstärkt", priceEuro = 250,
        installedAtKm = 19_123.0, installedOn = LocalDate.of(2026, 7, 11),
        tagNames = listOf("kupplung", "sachs"),
        checkpoints = listOf(
            PartService.CheckpointInput(100.0, "Kontrolle"),
            PartService.CheckpointInput(15_000.0, "Beläge prüfen"),
        ),
    )

    @Test
    fun `create persists the part with checkpoints, price in cents and upserted tags`() {
        val saved = createClutch()

        saved.priceCents shouldBe 25_000
        saved.checkpoints shouldHaveSize 2
        tags.findAllByUserIdOrderByName(userA).map { it.name } shouldBe listOf("kupplung", "sachs")

        // same tag name on another vehicle of the same user is reused, not duplicated
        val kombiId = vehicleService.create(userA, "Kombi", "#10b981").id!!
        service.create(
            userA, kombiId, name = "Kupplung LUK", details = null, priceEuro = null,
            installedAtKm = 80_000.0, installedOn = LocalDate.of(2026, 7, 1),
            tagNames = listOf("kupplung"), checkpoints = emptyList(),
        )
        tags.findAllByUserIdOrderByName(userA) shouldHaveSize 2
    }

    @Test
    fun `parseTagNames trims, lowercases and drops empties and duplicates`() {
        PartService.parseTagNames(" Kupplung, sachs , ,KUPPLUNG,") shouldBe listOf("kupplung", "sachs")
        PartService.parseTagNames(null) shouldBe emptyList()
    }

    @Test
    fun `update rebuilds open checkpoints from the form but keeps done ones`() {
        val created = createClutch()
        // mark one checkpoint done directly (checkOff flow is Task 6)
        val done = created.checkpoints.first { it.label == "Kontrolle" }
            .copy(doneOn = LocalDate.of(2026, 7, 12), doneAtKm = 19_223.0)
        parts.save(created.copy(checkpoints = created.checkpoints.map { if (it.label == "Kontrolle") done else it }.toSet()))

        val updated = service.update(
            userA, mopedId, created.id!!, name = "Kupplung Sachs", details = "verstärkt", priceEuro = 250,
            installedAtKm = 19_123.0, installedOn = LocalDate.of(2026, 7, 11),
            tagNames = listOf("kupplung"),
            checkpoints = listOf(PartService.CheckpointInput(20_000.0, "Großinspektion")),
        )

        updated.checkpoints.map { it.label }.sorted() shouldBe listOf("Großinspektion", "Kontrolle")
        updated.checkpoints.first { it.label == "Kontrolle" }.doneOn shouldBe LocalDate.of(2026, 7, 12)
        updated.tags shouldHaveSize 1
    }

    @Test
    fun `get rejects a foreign vehicle and a part of another vehicle with 404`() {
        val created = createClutch()
        val otherVehicle = vehicleService.create(userB, "Fremd", "#f43f5e").id!!

        shouldThrow<ResponseStatusException> { service.get(created.id!!, mopedId, userB) }
            .statusCode shouldBe HttpStatus.NOT_FOUND
        shouldThrow<ResponseStatusException> { service.get(created.id!!, otherVehicle, userB) }
            .statusCode shouldBe HttpStatus.NOT_FOUND
    }

    @Test
    fun `overviewFor resolves due items against the derived current km`() {
        createClutch()
        // trip-meter vehicle with a baseline: 19_000 + trip of 200 after the anchor -> 19_200
        vehicleService.update(mopedId, userA, "Moped", "#06b6d4", hasTripMeter = true,
            baselineKm = 19_000.0, baselineOn = LocalDate.of(2026, 7, 10))
        fuelEntries.save(FuelEntry(vehicleId = mopedId, date = LocalDate.of(2026, 7, 12), liters = 5.0, pricePerLiter = 1.7, kilometers = 200.0))

        val overview = service.overviewFor(userA, mopedId)

        overview.currentKm shouldBe 19_200.0
        overview.activeParts shouldHaveSize 1
        overview.retiredParts shouldHaveSize 0
        overview.dueItems shouldHaveSize 2
        overview.dueItems[0].checkpoint.label shouldBe "Kontrolle" // due at 19_123+100=19_223, current 19_200 -> 23 km left
        overview.dueItems[0].overdue shouldBe false
        overview.dueItems[0].remainingKm shouldBe 23.0
        overview.tagNamesByPartId[overview.activeParts.single().id!!] shouldBe listOf("kupplung", "sachs")
    }

    @Test
    fun `checkOff records the typed values on exactly the addressed checkpoint`() {
        val created = createClutch()
        val target = created.checkpoints.first { it.label == "Kontrolle" }

        service.checkOff(userA, mopedId, created.id!!, target.id!!, doneOn = LocalDate.of(2026, 7, 20), doneAtKm = 19_250.0)

        val reloaded = parts.findById(created.id!!).orElseThrow()
        val done = reloaded.checkpoints.first { it.label == "Kontrolle" }
        done.doneOn shouldBe LocalDate.of(2026, 7, 20)
        done.doneAtKm shouldBe 19_250.0
        reloaded.checkpoints.first { it.label == "Beläge prüfen" }.open shouldBe true
    }

    @Test
    fun `checkOff 404s for a checkpoint id that does not belong to the part`() {
        val created = createClutch()

        shouldThrow<ResponseStatusException> {
            service.checkOff(userA, mopedId, created.id!!, checkpointId = 999_999L, doneOn = LocalDate.of(2026, 7, 20), doneAtKm = null)
        }.statusCode shouldBe HttpStatus.NOT_FOUND
    }

    @Test
    fun `replace retires the old part and links the successor atomically`() {
        val old = createClutch()

        val successor = service.replace(
            userA, mopedId, old.id!!, name = "Kupplung LUK", details = null, priceEuro = 300,
            installedAtKm = 25_000.0, installedOn = LocalDate.of(2027, 1, 15),
            tagNames = listOf("kupplung"), checkpoints = listOf(PartService.CheckpointInput(100.0, "Kontrolle")),
        )

        val retired = parts.findById(old.id!!).orElseThrow()
        retired.active shouldBe false
        retired.retiredOn shouldBe LocalDate.of(2027, 1, 15)
        retired.retiredAtKm shouldBe 25_000.0
        retired.replacedByPartId shouldBe successor.id
        parts.findById(successor.id!!).orElseThrow().active shouldBe true
        // the retired part's open checkpoints no longer surface as due items
        service.overviewFor(userA, mopedId).dueItems.map { it.part.id } shouldBe listOf(successor.id)
    }

    @Test
    fun `delete removes the part and nulls successor references to it`() {
        val old = createClutch()
        val successor = service.replace(
            userA, mopedId, old.id!!, name = "Kupplung LUK", details = null, priceEuro = null,
            installedAtKm = 25_000.0, installedOn = LocalDate.of(2027, 1, 15),
            tagNames = emptyList(), checkpoints = emptyList(),
        )

        service.delete(userA, mopedId, successor.id!!)

        parts.findById(successor.id!!).isEmpty shouldBe true
        parts.findById(old.id!!).orElseThrow().replacedByPartId shouldBe null
    }
}
