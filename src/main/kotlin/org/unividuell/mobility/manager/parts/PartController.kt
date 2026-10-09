package org.unividuell.mobility.manager.parts

import jakarta.servlet.http.HttpServletRequest
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.server.ResponseStatusException
import org.unividuell.auth.AuthPrincipal
import org.unividuell.mobility.manager.fuel.FuelService
import org.unividuell.mobility.manager.user.CurrentUser
import org.unividuell.mobility.manager.vehicle.VehicleService
import java.time.LocalDate

@Controller
@RequestMapping("/vehicles/{vehicleId}/parts")
class PartController(
    private val service: PartService,
    private val vehicleService: VehicleService,
    private val currentUser: CurrentUser,
    private val fuelService: FuelService,
) {

    @GetMapping
    fun list(
        @AuthenticationPrincipal principal: AuthPrincipal,
        @PathVariable vehicleId: Long,
        model: Model,
    ): String {
        val userId = currentUser.require(principal).id!!
        model.addAttribute("vehicle", vehicleService.get(vehicleId, userId)) // 404 unless owned
        model.addAttribute("overview", service.overviewFor(userId, vehicleId))
        model.addAttribute("today", LocalDate.now())
        return "parts/list"
    }

    @GetMapping("/new")
    fun newForm(
        @AuthenticationPrincipal principal: AuthPrincipal,
        @PathVariable vehicleId: Long,
        @RequestParam(required = false) replaces: Long?,
        model: Model,
    ): String {
        val userId = currentUser.require(principal).id!!
        val vehicle = vehicleService.get(vehicleId, userId)
        // "replace" opens the same form prefilled from the predecessor (Task 10
        // wires the template side; passing null renders a plain new-part form)
        val predecessor = replaces?.let { service.get(it, vehicleId, userId) }
        model.addAttribute("vehicle", vehicle)
        model.addAttribute("part", null)
        model.addAttribute("predecessor", predecessor)
        model.addAttribute("prefillName", predecessor?.name)
        model.addAttribute("prefillTags", predecessor?.let { tagsAsInput(userId, it) })
        model.addAttribute("currentKm", fuelService.currentKm(vehicle))
        model.addAttribute("today", LocalDate.now())
        model.addAttribute("tagSuggestions", service.tagSuggestions(userId))
        return "parts/form"
    }

    @PostMapping
    fun create(
        @AuthenticationPrincipal principal: AuthPrincipal,
        @PathVariable vehicleId: Long,
        @RequestParam name: String,
        @RequestParam(required = false) details: String?,
        @RequestParam(required = false) priceEuro: Long?,
        @RequestParam installedAtKm: Double,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) installedOn: LocalDate,
        @RequestParam(required = false) tags: String?,
        @RequestParam(required = false) replacesPartId: Long?,
        request: HttpServletRequest,
    ): String {
        val userId = currentUser.require(principal).id!!
        val tagNames = PartService.parseTagNames(tags)
        val checkpoints = parseCheckpoints(rawParams(request, "checkpointOffsetKm"), rawParams(request, "checkpointLabel"))
        if (replacesPartId != null) {
            service.replace(userId, vehicleId, replacesPartId, name, details, priceEuro, installedAtKm, installedOn, tagNames, checkpoints)
        } else {
            service.create(userId, vehicleId, name, details, priceEuro, installedAtKm, installedOn, tagNames, checkpoints)
        }
        return "redirect:/vehicles/$vehicleId/parts"
    }

    @GetMapping("/{id}/edit")
    fun editForm(
        @AuthenticationPrincipal principal: AuthPrincipal,
        @PathVariable vehicleId: Long,
        @PathVariable id: Long,
        model: Model,
    ): String {
        val userId = currentUser.require(principal).id!!
        val vehicle = vehicleService.get(vehicleId, userId)
        val part = service.get(id, vehicleId, userId)
        model.addAttribute("vehicle", vehicle)
        model.addAttribute("part", part)
        model.addAttribute("predecessor", null)
        model.addAttribute("prefillName", part.name)
        model.addAttribute("prefillTags", tagsAsInput(userId, part))
        model.addAttribute("currentKm", fuelService.currentKm(vehicle))
        model.addAttribute("today", LocalDate.now())
        model.addAttribute("tagSuggestions", service.tagSuggestions(userId))
        return "parts/form"
    }

    @PostMapping("/{id}")
    fun update(
        @AuthenticationPrincipal principal: AuthPrincipal,
        @PathVariable vehicleId: Long,
        @PathVariable id: Long,
        @RequestParam name: String,
        @RequestParam(required = false) details: String?,
        @RequestParam(required = false) priceEuro: Long?,
        @RequestParam installedAtKm: Double,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) installedOn: LocalDate,
        @RequestParam(required = false) tags: String?,
        request: HttpServletRequest,
    ): String {
        val userId = currentUser.require(principal).id!!
        service.update(
            userId, vehicleId, id, name, details, priceEuro, installedAtKm, installedOn,
            PartService.parseTagNames(tags),
            parseCheckpoints(rawParams(request, "checkpointOffsetKm"), rawParams(request, "checkpointLabel")),
        )
        return "redirect:/vehicles/$vehicleId/parts"
    }

    @PostMapping("/{id}/delete")
    fun delete(
        @AuthenticationPrincipal principal: AuthPrincipal,
        @PathVariable vehicleId: Long,
        @PathVariable id: Long,
    ): String {
        val userId = currentUser.require(principal).id!!
        service.delete(userId, vehicleId, id)
        return "redirect:/vehicles/$vehicleId/parts"
    }

    @PostMapping("/{partId}/checkpoints/{checkpointId}/done")
    fun checkOff(
        @AuthenticationPrincipal principal: AuthPrincipal,
        @PathVariable vehicleId: Long,
        @PathVariable partId: Long,
        @PathVariable checkpointId: Long,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) doneOn: LocalDate,
        @RequestParam(required = false) doneAtKm: Double?,
    ): String {
        val userId = currentUser.require(principal).id!!
        service.checkOff(userId, vehicleId, partId, checkpointId, doneOn, doneAtKm)
        return "redirect:/vehicles/$vehicleId/parts"
    }

    /** The part's tags as the comma-string the form's text input expects. */
    private fun tagsAsInput(userId: Long, part: Part): String {
        val namesById = service.tagSuggestions(userId).associate { it.id!! to it.name }
        return part.tags.mapNotNull { namesById[it.tagId] }.sorted().joinToString(", ")
    }

    /**
     * Reads a repeated form parameter as the raw values the client submitted,
     * bypassing `@RequestParam List<String>` binding. Spring's String->Collection
     * converter kicks in whenever exactly one occurrence of a param is present
     * and comma-splits that lone value — for a blank value ("one filled row,
     * one blank row") that silently yields an empty list instead of `[""]`
     * (see `StringUtils.delimitedListToStringArray`, which special-cases empty
     * input), which would hide a half-filled checkpoint row instead of
     * rejecting it. `getParameterValues` returns exactly what was submitted.
     */
    private fun rawParams(request: HttpServletRequest, name: String): List<String>? =
        request.getParameterValues(name)?.toList()

    /**
     * Pairs the parallel checkpoint form arrays. Rows with both fields blank are
     * dropped (the always-present empty template row); half-filled rows are a 400.
     */
    private fun parseCheckpoints(offsets: List<String>?, labels: List<String>?): List<PartService.CheckpointInput> {
        val pairs = offsets.orEmpty().map { it.trim() }.zip(labels.orEmpty().map { it.trim() })
        return pairs.mapNotNull { (offset, label) ->
            when {
                offset.isEmpty() && label.isEmpty() -> null
                offset.isEmpty() || label.isEmpty() ->
                    throw ResponseStatusException(HttpStatus.BAD_REQUEST)
                else -> PartService.CheckpointInput(
                    offsetKm = offset.replace(',', '.').toDoubleOrNull()
                        ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST),
                    label = label,
                )
            }
        }
    }
}
