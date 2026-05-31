package org.unividuell.mobility.manager.fuel

import jakarta.servlet.http.HttpSession
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
import org.unividuell.mobility.manager.vehicle.VehicleContext
import org.unividuell.mobility.manager.vehicle.VehicleService
import java.time.LocalDate
import java.time.format.DateTimeFormatter

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
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) date: LocalDate?,
        session: HttpSession,
        model: Model,
    ): String {
        val userId = currentUser.require(principal).id!!
        val vehicles = vehicleService.listFor(userId)
        // the vehicle is the globally selected one, not part of the typed input
        val selectedId = vehicleContext.current(session, userId)?.id
        val current = FuelDraft(liters, pricePerLiter, kilometers, selectedId, date).withDefaults()

        when (val result = service.applyValue(current, value)) {
            is FuelService.DraftResult.Completed ->
                renderPanel(
                    model, vehicles, FuelDraft(vehicleId = selectedId).withDefaults(),
                    saved = result.saved, delta = service.consumptionDelta(result.saved),
                )
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
        service.undo(id, vehicles.mapNotNull { it.id }.toSet())
        renderPanel(model, vehicles, freshDraft(session, userId), saved = null)
        return "fragments/panel :: panel"
    }

    /** An empty draft seeded with the selected vehicle and today's date. */
    private fun freshDraft(session: HttpSession, userId: Long): FuelDraft =
        FuelDraft(vehicleId = vehicleContext.current(session, userId)?.id).withDefaults()

    /** The only part pre-filled now is the date — it defaults to today. */
    private fun FuelDraft.withDefaults(): FuelDraft =
        if (date == null) copy(date = LocalDate.now()) else this

    /** Shared model for the swappable panel: vehicles for the picker, the draft/entry, and display helpers. */
    private fun renderPanel(
        model: Model,
        vehicles: List<Vehicle>,
        draft: FuelDraft,
        saved: FuelEntry?,
        delta: ConsumptionDelta? = null,
    ) {
        model.addAttribute("vehicles", vehicles)
        model.addAttribute("draft", draft)
        model.addAttribute("saved", saved)
        // consumption trend vs. the previous refueling (only meaningful on a saved entry)
        model.addAttribute("consumptionDelta", delta)
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
