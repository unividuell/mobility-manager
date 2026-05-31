package org.unividuell.mobility.manager

/**
 * The app's accent color, derived from the active vehicle's color so the whole UI
 * is tinted to the vehicle in context. `color` tints borders/text/buttons app-wide;
 * `onColor` is a legible foreground for text placed *on top* of `color` (e.g. button
 * labels), chosen near-black or near-white by the color's perceived brightness.
 */
data class Accent(val color: String, val onColor: String) {
    companion object {
        fun of(hex: String) = Accent(color = hex, onColor = foregroundFor(hex))

        private fun foregroundFor(hex: String): String {
            val h = hex.removePrefix("#")
            if (h.length != 6) return DARK
            return runCatching {
                val r = h.substring(0, 2).toInt(16)
                val g = h.substring(2, 4).toInt(16)
                val b = h.substring(4, 6).toInt(16)
                // YIQ perceived brightness: bright accents get near-black text, dark ones near-white.
                if ((r * 299 + g * 587 + b * 114) / 1000 >= 140) DARK else LIGHT
            }.getOrDefault(DARK)
        }

        private const val DARK = "#09090b"  // zinc-950, matches the app background
        private const val LIGHT = "#fafafa" // zinc-50
    }
}
