// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.skin

import androidx.compose.material3.ColorScheme

/**
 * Wallpaper-shaped schemes, for testing the contrast pairs away from the
 * shipped palettes.
 *
 * A port of `scheme_at` in `skin/verify/sweep.py`. Contrast is made of tone,
 * and every M3 variant assigns the same tones to the same roles; a wallpaper
 * varies the hue of the source and how much chroma each family carries. So the
 * tones are measured off the shipped roles and only hue and chroma move.
 * Chroma 0 is Monochrome.
 */
object SyntheticSchemes {
    /** Which tonal palette each M3 role is drawn from; the prefix says which. */
    private val FAMILY_BY_PREFIX =
        listOf(
            "on_primary_fixed" to "primary",
            "primary_fixed" to "primary",
            "on_primary" to "primary",
            "primary" to "primary",
            "inverse_primary" to "primary",
            "on_secondary" to "secondary",
            "secondary" to "secondary",
            "on_tertiary" to "tertiary",
            "tertiary" to "tertiary",
            "on_error" to "error",
            "error" to "error",
            "on_surface_variant" to "neutral_variant",
            "surface_variant" to "neutral_variant",
            "outline" to "neutral_variant",
            "on_surface" to "neutral",
            "surface" to "neutral",
            "inverse_on_surface" to "neutral",
            "inverse_surface" to "neutral",
        )

    /** Chroma per family, as a multiple of the source's. */
    private val CHROMA =
        mapOf(
            "primary" to 1.00,
            "secondary" to 0.34,
            "tertiary" to 0.50,
            "neutral" to 0.06,
            "neutral_variant" to 0.14,
            "error" to 1.0,
        )

    private const val ERROR_HUE = 25.0
    private const val TERTIARY_SHIFT = 60.0

    private fun family(role: String) = FAMILY_BY_PREFIX.firstOrNull { role.startsWith(it.first) }?.second ?: "neutral"

    /** One synthetic scheme for [theme]: its baseline tones, restated at this [hue] and [chroma]. */
    fun schemeAt(
        theme: String,
        hue: Double,
        chroma: Double,
    ): ColorScheme {
        val shipped = SkinDist.roles(theme)
        return SkinDist.scheme(dark = theme != "light") { role ->
            val lightness = Tonal.toLab(shipped.getValue(role))[0]
            val family = family(role)
            val h =
                when (family) {
                    "error" -> ERROR_HUE
                    "tertiary" -> (hue + TERTIARY_SHIFT) % 360
                    else -> hue
                }
            Tonal.fromLch(lightness, chroma * CHROMA.getValue(family), h)
        }
    }
}
