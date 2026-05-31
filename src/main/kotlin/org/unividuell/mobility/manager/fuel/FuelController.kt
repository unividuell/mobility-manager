package org.unividuell.mobility.manager.fuel

import org.springframework.format.annotation.DateTimeFormat
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.core.user.OAuth2User
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.unividuell.mobility.manager.user.CurrentUser
import org.unividuell.mobility.manager.vehicle.Vehicle
import org.unividuell.mobility.manager.vehicle.VehicleService
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@Controller
@RequestMapping("/")
class FuelController(
    private val service: FuelService,
    private val vehicleService: VehicleService,
    private val currentUser: CurrentUser,
) {

    @GetMapping
    fun index(@AuthenticationPrincipal principal: OAuth2User, model: Model): String {
        val vehicles = vehiclesOf(principal)
        renderPanel(model, vehicles, FuelDraft().withDefaults(vehicles), saved = null)
        return "index"
    }

    @PostMapping("/fuel/value")
    fun submitValue(
        @AuthenticationPrincipal principal: OAuth2User,
        @RequestParam value: String,
        @RequestParam(required = false) liters: Double?,
        @RequestParam(required = false) pricePerLiter: Double?,
        @RequestParam(required = false) kilometers: Double?,
        @RequestParam(required = false) vehicleId: Long?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) date: LocalDate?,
        model: Model,
    ): String {
        val userId = currentUser.require(principal).id!!
        val vehicles = vehicleService.listFor(userId)
        val current = FuelDraft(liters, pricePerLiter, kilometers, vehicleId, date).withDefaults(vehicles)

        when (val result = service.applyValue(current, value) { query -> vehicleService.resolve(userId, query)?.id }) {
            is FuelService.DraftResult.Completed ->
                renderPanel(model, vehicles, FuelDraft().withDefaults(vehicles), saved = result.saved)
            is FuelService.DraftResult.Pending ->
                renderPanel(model, vehicles, result.draft, saved = null)
        }
        return "fragments/panel :: panel"
    }

    @PostMapping("/fuel/reset")
    fun reset(@AuthenticationPrincipal principal: OAuth2User, model: Model): String {
        val vehicles = vehiclesOf(principal)
        renderPanel(model, vehicles, FuelDraft().withDefaults(vehicles), saved = null)
        return "fragments/panel :: panel"
    }

    @PostMapping("/fuel/undo")
    fun undo(@AuthenticationPrincipal principal: OAuth2User, @RequestParam id: Long, model: Model): String {
        val vehicles = vehiclesOf(principal)
        service.undo(id, vehicles.mapNotNull { it.id }.toSet())
        renderPanel(model, vehicles, FuelDraft().withDefaults(vehicles), saved = null)
        return "fragments/panel :: panel"
    }

    private fun vehiclesOf(principal: OAuth2User): List<Vehicle> =
        vehicleService.listFor(currentUser.require(principal).id!!)

    /**
     * Pre-fills the parts the user shouldn't have to type: the date defaults to
     * today, and a sole vehicle is pre-selected so a fuel entry needs only the
     * three numbers.
     */
    private fun FuelDraft.withDefaults(vehicles: List<Vehicle>): FuelDraft {
        var draft = this
        if (draft.date == null) draft = draft.copy(date = LocalDate.now())
        if (draft.vehicleId == null && vehicles.size == 1) draft = draft.copy(vehicleId = vehicles.single().id)
        return draft
    }

    /** Shared model for the swappable panel: vehicles for the picker, the draft/entry, and display helpers. */
    private fun renderPanel(model: Model, vehicles: List<Vehicle>, draft: FuelDraft, saved: FuelEntry?) {
        model.addAttribute("vehicles", vehicles)
        model.addAttribute("draft", draft)
        model.addAttribute("saved", saved)
        // the vehicle/date currently picked in the draft or linked to the saved entry, for display
        val pickedId = saved?.vehicleId ?: draft.vehicleId
        model.addAttribute("pickedVehicle", vehicles.firstOrNull { it.id == pickedId })
        val pickedDate = saved?.date ?: draft.date
        model.addAttribute("dateDisplay", pickedDate?.format(GERMAN_DATE))
    }

    private companion object {
        val GERMAN_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")
    }
}
