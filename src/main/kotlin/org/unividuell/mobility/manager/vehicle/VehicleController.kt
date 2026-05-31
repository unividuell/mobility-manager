package org.unividuell.mobility.manager.vehicle

import jakarta.servlet.http.HttpSession
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.core.user.OAuth2User
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseBody
import org.unividuell.mobility.manager.user.CurrentUser

@Controller
@RequestMapping("/vehicles")
class VehicleController(
    private val service: VehicleService,
    private val vehicleContext: VehicleContext,
    private val currentUser: CurrentUser,
) {

    @GetMapping
    fun index(@AuthenticationPrincipal principal: OAuth2User, model: Model): String {
        val userId = currentUser.require(principal).id!!
        model.addAttribute("vehicles", service.listFor(userId))
        return "vehicles/index"
    }

    @GetMapping("/new")
    fun newForm(model: Model): String {
        model.addAttribute("vehicle", null)
        return "vehicles/form"
    }

    @PostMapping
    fun create(
        @AuthenticationPrincipal principal: OAuth2User,
        @RequestParam name: String,
        @RequestParam color: String,
    ): String {
        val userId = currentUser.require(principal).id!!
        service.create(userId, name, color)
        return "redirect:/vehicles"
    }

    @PostMapping("/{id}/select")
    fun select(
        @AuthenticationPrincipal principal: OAuth2User,
        @PathVariable id: Long,
        session: HttpSession,
    ): String {
        val userId = currentUser.require(principal).id!!
        service.get(id, userId) // 404s unless the vehicle belongs to the user
        vehicleContext.select(session, id)
        return "redirect:/vehicles"
    }

    @GetMapping("/{id}/edit")
    fun editForm(
        @AuthenticationPrincipal principal: OAuth2User,
        @PathVariable id: Long,
        model: Model,
    ): String {
        val userId = currentUser.require(principal).id!!
        model.addAttribute("vehicle", service.get(id, userId))
        return "vehicles/form"
    }

    @PostMapping("/{id}")
    fun update(
        @AuthenticationPrincipal principal: OAuth2User,
        @PathVariable id: Long,
        @RequestParam name: String,
        @RequestParam color: String,
    ): String {
        val userId = currentUser.require(principal).id!!
        service.update(id, userId, name, color)
        return "redirect:/vehicles"
    }

    @DeleteMapping("/{id}")
    @ResponseBody
    fun delete(
        @AuthenticationPrincipal principal: OAuth2User,
        @PathVariable id: Long,
    ): String {
        val userId = currentUser.require(principal).id!!
        service.delete(id, userId)
        // htmx swaps the card with this empty response (outerHTML → removed).
        return ""
    }
}
