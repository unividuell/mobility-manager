package org.unividuell.mobility.manager.parts

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.unividuell.mobility.manager.DatabaseCleaner
import org.unividuell.mobility.manager.user.AppUserService
import org.unividuell.mobility.manager.vehicle.VehicleService
import java.time.LocalDate

@SpringBootTest
@ActiveProfiles("test")
class PartRepositoryIntegrationTest @Autowired constructor(
    private val parts: PartRepository,
    private val tags: TagRepository,
    private val vehicleService: VehicleService,
    private val users: AppUserService,
    private val db: DatabaseCleaner,
) {

    private var userId = 0L
    private var vehicleId = 0L

    @BeforeEach
    fun cleanDb() {
        db.clean()
        userId = users.upsert(provider = "github", subject = "1001", login = "alice", name = "Alice").id!!
        vehicleId = vehicleService.create(userId, "Moped", "#06b6d4").id!!
    }

    @Test
    fun `saves and reloads the full aggregate with checkpoints and tag refs`() {
        val tag = tags.save(Tag(userId = userId, name = "kupplung"))
        val saved = parts.save(
            Part(
                vehicleId = vehicleId,
                name = "Kupplung Sachs",
                details = "Verstärkte Ausführung",
                priceCents = 25_000,
                installedAtKm = 19_123.0,
                installedOn = LocalDate.of(2026, 7, 11),
                checkpoints = setOf(
                    PartCheckpoint(offsetKm = 100.0, label = "Kontrolle"),
                    PartCheckpoint(offsetKm = 15_000.0, label = "Beläge prüfen"),
                ),
                tags = setOf(PartTagRef(tagId = tag.id!!)),
            ),
        )

        val reloaded = parts.findById(saved.id!!).orElseThrow()
        reloaded.name shouldBe "Kupplung Sachs"
        reloaded.priceEuro shouldBe 250
        reloaded.active shouldBe true
        reloaded.checkpoints shouldHaveSize 2
        reloaded.checkpoints.all { it.open } shouldBe true
        reloaded.tags.single().tagId shouldBe tag.id
    }

    @Test
    fun `deleting the referenced successor nulls replacedByPartId`() {
        val successor = parts.save(part(name = "Kupplung neu"))
        val old = parts.save(part(name = "Kupplung alt").copy(replacedByPartId = successor.id))

        parts.deleteById(successor.id!!)

        parts.findById(old.id!!).orElseThrow().replacedByPartId shouldBe null
    }

    @Test
    fun `tag names are unique per user`() {
        tags.save(Tag(userId = userId, name = "kupplung"))
        tags.findByUserIdAndName(userId, "kupplung")!!.name shouldBe "kupplung"
        tags.findByUserIdAndName(userId, "bremse") shouldBe null
    }

    private fun part(name: String) = Part(
        vehicleId = vehicleId, name = name, details = null, priceCents = null,
        installedAtKm = 0.0, installedOn = LocalDate.of(2026, 1, 1),
    )
}
