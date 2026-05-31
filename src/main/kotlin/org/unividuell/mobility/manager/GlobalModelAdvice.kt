package org.unividuell.mobility.manager

import jakarta.servlet.http.HttpSession
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.core.user.OAuth2User
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ModelAttribute
import org.unividuell.mobility.manager.user.CurrentUser
import org.unividuell.mobility.manager.vehicle.Vehicle
import org.unividuell.mobility.manager.vehicle.VehicleContext

/**
 * Exposes the session-selected vehicle to every view as `selectedVehicle`, so the
 * shared header shows the active vehicle context on every page without each
 * controller having to add it.
 */
@ControllerAdvice
class GlobalModelAdvice(
    private val currentUser: CurrentUser,
    private val vehicleContext: VehicleContext,
) {

    @ModelAttribute("selectedVehicle")
    fun selectedVehicle(
        @AuthenticationPrincipal principal: OAuth2User?,
        session: HttpSession,
    ): Vehicle? {
        principal ?: return null
        val userId = currentUser.require(principal).id!!
        return vehicleContext.current(session, userId)
    }
}
