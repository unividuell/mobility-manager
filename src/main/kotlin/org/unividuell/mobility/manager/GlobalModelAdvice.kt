package org.unividuell.mobility.manager

import jakarta.servlet.http.HttpSession
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.core.user.OAuth2User
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ModelAttribute
import org.unividuell.mobility.manager.user.CurrentUser
import org.unividuell.mobility.manager.vehicle.VehicleContext

/**
 * Exposes the session-selected vehicle to every view as `selectedVehicle` (so the
 * shared header shows the active context on every page) along with the `accent`
 * color derived from it — letting the whole UI tint to the vehicle in context.
 */
@ControllerAdvice
class GlobalModelAdvice(
    private val currentUser: CurrentUser,
    private val vehicleContext: VehicleContext,
) {

    @ModelAttribute
    fun populate(
        @AuthenticationPrincipal principal: OAuth2User?,
        session: HttpSession,
        model: Model,
    ) {
        val vehicle = principal?.let {
            vehicleContext.current(session, currentUser.require(it).id!!)
        }
        model.addAttribute("selectedVehicle", vehicle)
        model.addAttribute("accent", vehicle?.let { Accent.of(it.color) })
    }
}
