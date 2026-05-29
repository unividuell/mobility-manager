package org.unividuell.mobility.manager.fuel

import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam

@Controller
@RequestMapping("/")
class FuelController(
    private val service: FuelService,
) {

    @GetMapping
    fun index(model: Model): String {
        model.addAttribute("draft", FuelDraft())
        model.addAttribute("saved", null)
        return "index"
    }

    @PostMapping("/fuel/value")
    fun submitValue(
        @RequestParam value: String,
        @RequestParam(required = false) liters: Double?,
        @RequestParam(required = false) pricePerLiter: Double?,
        @RequestParam(required = false) kilometers: Double?,
        model: Model,
    ): String {
        val current = FuelDraft(liters, pricePerLiter, kilometers)
        when (val result = service.applyValue(current, value)) {
            is FuelService.DraftResult.Completed -> {
                model.addAttribute("draft", FuelDraft())
                model.addAttribute("saved", result.saved)
            }
            is FuelService.DraftResult.Pending -> {
                model.addAttribute("draft", result.draft)
                model.addAttribute("saved", null)
            }
        }
        return "fragments/panel :: panel"
    }

    @PostMapping("/fuel/reset")
    fun reset(model: Model): String {
        model.addAttribute("draft", FuelDraft())
        model.addAttribute("saved", null)
        return "fragments/panel :: panel"
    }
}
