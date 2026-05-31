package org.unividuell.mobility.manager.fuel

import jakarta.servlet.http.HttpSession
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.core.user.OAuth2User
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.server.ResponseStatusException
import org.unividuell.mobility.manager.user.CurrentUser
import org.unividuell.mobility.manager.vehicle.Vehicle
import org.unividuell.mobility.manager.vehicle.VehicleContext
import org.unividuell.mobility.manager.vehicle.VehicleService
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

@Controller
@RequestMapping("/")
class FuelController(
    private val service: FuelService,
    private val vehicleService: VehicleService,
    private val vehicleContext: VehicleContext,
    private val currentUser: CurrentUser,
) {

    @GetMapping
    fun index(@AuthenticationPrincipal principal: OAuth2User, session: HttpSession, model: Model): String {
        val userId = currentUser.require(principal).id!!
        renderPanel(model, vehicleService.listFor(userId), freshDraft(session, userId), saved = null)
        return "index"
    }

    @PostMapping("/fuel/value")
    fun submitValue(
        @AuthenticationPrincipal principal: OAuth2User,
        @RequestParam value: String,
        @RequestParam(required = false) liters: Double?,
        @RequestParam(required = false) pricePerLiter: Double?,
        @RequestParam(required = false) kilometers: Double?,
        @RequestParam(required = false) odometer: Double?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) date: LocalDate?,
        session: HttpSession,
        model: Model,
    ): String {
        val userId = currentUser.require(principal).id!!
        val vehicles = vehicleService.listFor(userId)
        // the vehicle is the globally selected one, not part of the typed input; its
        // trip-meter mode decides whether the distance slot is a trip or an odometer.
        val selected = vehicleContext.current(session, userId)
        val current = FuelDraft(
            liters = liters, pricePerLiter = pricePerLiter,
            kilometers = kilometers, odometer = odometer,
            vehicleId = selected?.id, date = date,
            hasTripMeter = selected?.hasTripMeter ?: true,
        ).withDefaults()

        when (val result = service.applyValue(current, value)) {
            is FuelService.DraftResult.Completed -> {
                val summary = service.summarize(result.saved)
                renderPanel(model, vehicles, freshDraft(selected), saved = summary.point, delta = summary.delta)
            }
            is FuelService.DraftResult.Pending ->
                renderPanel(model, vehicles, result.draft, saved = null)
        }
        return "fragments/panel :: panel"
    }

    @PostMapping("/fuel/reset")
    fun reset(@AuthenticationPrincipal principal: OAuth2User, session: HttpSession, model: Model): String {
        val userId = currentUser.require(principal).id!!
        renderPanel(model, vehicleService.listFor(userId), freshDraft(session, userId), saved = null)
        return "fragments/panel :: panel"
    }

    @PostMapping("/fuel/undo")
    fun undo(
        @AuthenticationPrincipal principal: OAuth2User,
        @RequestParam id: Long,
        session: HttpSession,
        model: Model,
    ): String {
        val userId = currentUser.require(principal).id!!
        val vehicles = vehicleService.listFor(userId)
        service.delete(id, vehicles.mapNotNull { it.id }.toSet())
        renderPanel(model, vehicles, freshDraft(session, userId), saved = null)
        return "fragments/panel :: panel"
    }

    @GetMapping("/vehicles/{vehicleId}/fuel")
    fun list(
        @AuthenticationPrincipal principal: OAuth2User,
        @PathVariable vehicleId: Long,
        model: Model,
    ): String {
        val userId = currentUser.require(principal).id!!
        val vehicle = vehicleService.get(vehicleId, userId) // 404 unless the user owns it
        val points = service.timeline(vehicleId)            // newest first, distance/consumption resolved
        val maxConsumption = points.mapNotNull { it.consumptionPer100Km }.maxOrNull() ?: 0.0
        val rows = points.map { it.toRow(maxConsumption) }
        model.addAttribute("vehicle", vehicle)
        model.addAttribute("rows", rows)                    // table: newest first
        model.addAttribute("chartRows", rows.reversed())    // chart: oldest → newest
        return "fuel/list"
    }

    @GetMapping("/vehicles/{vehicleId}/fuel/{id}/edit")
    fun editForm(
        @AuthenticationPrincipal principal: OAuth2User,
        @PathVariable vehicleId: Long,
        @PathVariable id: Long,
        model: Model,
    ): String {
        val userId = currentUser.require(principal).id!!
        val vehicle = vehicleService.get(vehicleId, userId) // 404 unless the user owns it
        val entry = service.find(id, setOf(vehicleId)) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
        model.addAttribute("vehicle", vehicle)
        model.addAttribute("entry", entry)
        return "fuel/edit"
    }

    @PostMapping("/vehicles/{vehicleId}/fuel/{id}")
    fun updateEntry(
        @AuthenticationPrincipal principal: OAuth2User,
        @PathVariable vehicleId: Long,
        @PathVariable id: Long,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) date: LocalDate,
        @RequestParam liters: Double,
        @RequestParam pricePerLiter: Double,
        @RequestParam(required = false) kilometers: Double?,
        @RequestParam(required = false) odometer: Double?,
    ): String {
        val userId = currentUser.require(principal).id!!
        vehicleService.get(vehicleId, userId) // 404 unless the user owns it
        service.update(id, setOf(vehicleId), date, liters, pricePerLiter, kilometers, odometer)
        return "redirect:/vehicles/$vehicleId/fuel"
    }

    @PostMapping("/vehicles/{vehicleId}/fuel/{id}/delete")
    fun deleteEntry(
        @AuthenticationPrincipal principal: OAuth2User,
        @PathVariable vehicleId: Long,
        @PathVariable id: Long,
    ): String {
        val userId = currentUser.require(principal).id!!
        service.delete(id, vehicleService.listFor(userId).mapNotNull { it.id }.toSet())
        return "redirect:/vehicles/$vehicleId/fuel"
    }

    private fun FuelPoint.toRow(maxConsumption: Double) = FuelListRow(
        id = id!!,
        dateDisplay = date.format(SHORT_DATE),
        consumption = consumptionPer100Km,
        liters = liters,
        pricePerLiter = pricePerLiter,
        kilometers = distanceKm,
        totalCost = totalCost,
        barHeightPercent = if (maxConsumption > 0 && consumptionPer100Km != null) {
            (consumptionPer100Km / maxConsumption * 100).roundToInt()
        } else {
            0
        },
    )

    /** An empty draft seeded with the selected vehicle's id and trip-meter mode, plus today's date. */
    private fun freshDraft(session: HttpSession, userId: Long): FuelDraft =
        freshDraft(vehicleContext.current(session, userId))

    private fun freshDraft(vehicle: Vehicle?): FuelDraft =
        FuelDraft(vehicleId = vehicle?.id, hasTripMeter = vehicle?.hasTripMeter ?: true).withDefaults()

    /** The only part pre-filled now is the date — it defaults to today. */
    private fun FuelDraft.withDefaults(): FuelDraft =
        if (date == null) copy(date = LocalDate.now()) else this

    /** Shared model for the swappable panel: vehicles for the picker, the draft/entry, and display helpers. */
    private fun renderPanel(
        model: Model,
        vehicles: List<Vehicle>,
        draft: FuelDraft,
        saved: FuelPoint?,
        delta: ConsumptionDelta? = null,
    ) {
        model.addAttribute("vehicles", vehicles)
        model.addAttribute("draft", draft)
        model.addAttribute("saved", saved)
        // consumption trend vs. the previous refueling (only meaningful on a saved entry)
        model.addAttribute("consumptionDelta", delta)
        // the vehicle/date currently picked in the draft or linked to the saved entry, for display
        val pickedId = saved?.entry?.vehicleId ?: draft.vehicleId
        model.addAttribute("pickedVehicle", vehicles.firstOrNull { it.id == pickedId })
        val pickedDate = saved?.date ?: draft.date
        model.addAttribute("dateDisplay", pickedDate?.format(GERMAN_DATE))
    }

    private companion object {
        val GERMAN_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")
        val SHORT_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yy")
    }
}
