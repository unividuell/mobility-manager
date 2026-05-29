package org.unividuell.mobility.manager.fuel

import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.core.user.OAuth2User
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.unividuell.mobility.manager.user.CurrentUser
import org.unividuell.mobility.manager.vehicle.VehicleService

@Controller
@RequestMapping("/")
class FuelController(
    private val service: FuelService,
    private val vehicleService: VehicleService,
    private val currentUser: CurrentUser,
) {

    @GetMapping
    fun index(@AuthenticationPrincipal principal: OAuth2User, model: Model): String {
        populatePanel(principal, model, FuelDraft(), saved = null)
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
        model: Model,
    ): String {
        val userId = currentUser.require(principal).id!!
        val current = FuelDraft(liters, pricePerLiter, kilometers, vehicleId)

        when (val result = service.applyValue(current, value) { query -> vehicleService.resolve(userId, query)?.id }) {
            is FuelService.DraftResult.Completed ->
                populatePanel(principal, model, FuelDraft(), saved = result.saved)
            is FuelService.DraftResult.Pending ->
                populatePanel(principal, model, result.draft, saved = null)
        }
        return "fragments/panel :: panel"
    }

    @PostMapping("/fuel/reset")
    fun reset(@AuthenticationPrincipal principal: OAuth2User, model: Model): String {
        populatePanel(principal, model, FuelDraft(), saved = null)
        return "fragments/panel :: panel"
    }

    /** Shared model for the swappable panel: vehicles for the picker, the draft, and any saved entry. */
    private fun populatePanel(principal: OAuth2User, model: Model, draft: FuelDraft, saved: FuelEntry?) {
        val userId = currentUser.require(principal).id!!
        val vehicles = vehicleService.listFor(userId)
        model.addAttribute("vehicles", vehicles)
        model.addAttribute("draft", draft)
        model.addAttribute("saved", saved)
        // the vehicle currently picked in the draft / linked to the saved entry, for display
        val pickedId = saved?.vehicleId ?: draft.vehicleId
        model.addAttribute("pickedVehicle", vehicles.firstOrNull { it.id == pickedId })
    }
}
