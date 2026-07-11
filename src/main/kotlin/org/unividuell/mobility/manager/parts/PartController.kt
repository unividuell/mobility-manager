package org.unividuell.mobility.manager.parts

import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.core.user.OAuth2User
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.unividuell.mobility.manager.user.CurrentUser
import org.unividuell.mobility.manager.vehicle.VehicleService
import java.time.LocalDate

@Controller
@RequestMapping("/vehicles/{vehicleId}/parts")
class PartController(
    private val service: PartService,
    private val vehicleService: VehicleService,
    private val currentUser: CurrentUser,
) {

    @GetMapping
    fun list(
        @AuthenticationPrincipal principal: OAuth2User,
        @PathVariable vehicleId: Long,
        model: Model,
    ): String {
        val userId = currentUser.require(principal).id!!
        model.addAttribute("vehicle", vehicleService.get(vehicleId, userId)) // 404 unless owned
        model.addAttribute("overview", service.overviewFor(userId, vehicleId))
        model.addAttribute("today", LocalDate.now())
        return "parts/list"
    }
}
