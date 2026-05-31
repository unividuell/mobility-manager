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
import org.unividuell.mobility.manager.fuel.FuelEntryRepository
import org.unividuell.mobility.manager.user.AppUserRepository
import org.unividuell.mobility.manager.user.AppUserService

@SpringBootTest
@ActiveProfiles("test")
class VehicleServiceIntegrationTest @Autowired constructor(
    private val service: VehicleService,
    private val repository: VehicleRepository,
    private val fuelEntries: FuelEntryRepository,
    private val users: AppUserService,
    private val userRepository: AppUserRepository,
) {

    // FKs are enforced, so the manager ids must be real users.
    private var userA = 0L
    private var userB = 0L

    @BeforeEach
    fun cleanDb() {
        // dependency order: fuel_entries -> vehicles (cascades vehicle_managers) -> users
        fuelEntries.deleteAll()
        repository.deleteAll()
        userRepository.deleteAll()
        userA = users.upsert(1001L, login = "alice", displayName = "Alice").id!!
        userB = users.upsert(1002L, login = "bob", displayName = "Bob").id!!
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
}
