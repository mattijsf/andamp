// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.theme

import androidx.compose.material3.ColorScheme
import nl.mattix.andamp.skin.Skin
import java.util.WeakHashMap

/**
 * A skin's Material scheme, computed once per skin for the whole process.
 *
 * Quantizing MAIN.BMP takes milliseconds on the main thread, and `remember` holds the result only
 * for one composition. The activity and the floating window are two compositions of the player, so
 * the cache is shared here.
 *
 * The map is weak on the skin, so a scheme lives as long as the art it came from. Access is
 * synchronized.
 */
object SkinSchemes {
    private data class Variant(
        val dark: Boolean,
        val style: SkinSchemeStyle,
    )

    private val byArt = WeakHashMap<Skin, MutableMap<Variant, ColorScheme>>()

    /**
     * [skin]'s scheme, built the first time this process asks. Tests pass their own [build] to
     * count calls.
     */
    fun of(
        skin: Skin,
        dark: Boolean = true,
        style: SkinSchemeStyle = SkinSchemeStyle.VIBRANT,
        build: (Skin, Boolean, SkinSchemeStyle) -> ColorScheme = SkinColors::schemeOf,
    ): ColorScheme =
        synchronized(byArt) {
            byArt
                .getOrPut(skin) { mutableMapOf() }
                .getOrPut(Variant(dark, style)) { build(skin, dark, style) }
        }

    /** Drops everything cached; for tests. */
    fun forget() = synchronized(byArt) { byArt.clear() }
}
