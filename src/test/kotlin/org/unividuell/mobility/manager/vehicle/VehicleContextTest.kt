package org.unividuell.mobility.manager.vehicle

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpSession

/**
 * Unit test for [VehicleContext]. The session is a real [MockHttpSession] (so we
 * can assert what gets persisted), the vehicle list is mocked.
 */
class VehicleContextTest {

    private val userId = 1L
    private val key = "selectedVehicleId"

    private fun vehicle(id: Long, name: String) =
        Vehicle(id = id, name = name, color = "#06b6d4", managers = setOf(VehicleManager(userId)))

    private fun context(vehicles: List<Vehicle>): VehicleContext {
        val service = mockk<VehicleService>()
        every { service.listFor(userId) } returns vehicles
        return VehicleContext(service)
    }

    @Test
    fun `auto-selects and persists the sole vehicle`() {
        val session = MockHttpSession()
        val ctx = context(listOf(vehicle(7L, "Kombi")))

        ctx.current(session, userId)?.id shouldBe 7L
        session.getAttribute(key) shouldBe 7L
    }

    @Test
    fun `keeps a stored selection that still belongs to the user`() {
        val session = MockHttpSession().apply { setAttribute(key, 9L) }
        val ctx = context(listOf(vehicle(7L, "Kombi"), vehicle(9L, "Roadster")))

        ctx.current(session, userId)?.id shouldBe 9L
    }

    @Test
    fun `discards a stored id that no longer belongs to the user`() {
        val session = MockHttpSession().apply { setAttribute(key, 99L) }
        val ctx = context(listOf(vehicle(7L, "Kombi"), vehicle(9L, "Roadster")))

        ctx.current(session, userId).shouldBeNull()
        session.getAttribute(key).shouldBeNull()
    }

    @Test
    fun `returns null when several vehicles exist and none is selected`() {
        val session = MockHttpSession()
        val ctx = context(listOf(vehicle(7L, "Kombi"), vehicle(9L, "Roadster")))

        ctx.current(session, userId).shouldBeNull()
        session.getAttribute(key).shouldBeNull()
    }

    @Test
    fun `select stores the vehicle id in the session`() {
        val session = MockHttpSession()
        val ctx = context(emptyList())

        ctx.select(session, 5L)

        session.getAttribute(key) shouldBe 5L
    }
}
