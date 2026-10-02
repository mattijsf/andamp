// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.skin

/**
 * The template a bundled skin is rebuilt from with the phone's own palette; see [SkinTemplate].
 * [dark] is the entry's and not the phone's: AndAmp Dark asks for the dynamic dark scheme whatever
 * the night setting says.
 */
data class LiveTemplate(
    val asset: String,
    val dark: Boolean,
)

/**
 * A skin that ships inside the app: an id, a name, the asset holding its bytes and, for the two
 * that can follow the phone's palette, the template that rebuilds it.
 */
data class BundledSkin(
    val id: String,
    val name: String,
    val asset: String,
    val live: LiveTemplate? = null,
)

/**
 * The skins Andamp ships, and the one it falls back to.
 *
 * They are generated: `skin/` in this repository builds all three from one Python source, the same
 * geometry in three color tables.
 *
 * [BASE] is what a fresh install wears and what a skin missing a sheet borrows from.
 */
object BundledSkins {
    /** The id stored in preferences for [BASE]. */
    const val BASE_ID = "base"

    val BASE =
        BundledSkin(
            BASE_ID,
            "AndAmp Dark",
            "skins/AndAmp Dark.wsz",
            live = LiveTemplate("skins/andamp-dark-template.zip", dark = true),
        )

    /** The same design in a light scheme: the same geometry, another color table. */
    val LIGHT =
        BundledSkin(
            "andamp-light",
            "AndAmp Light",
            "skins/AndAmp Light.wsz",
            live = LiveTemplate("skins/andamp-light-template.zip", dark = false),
        )

    /**
     * Winamp's LCD green and analyzer amber as seed colors. Not live: it keeps those seeds whatever
     * the wallpaper.
     */
    val SPOT = BundledSkin("andamp-spot", "AndAmp Spot", "skins/AndAmp Spot.wsz")

    val all = listOf(BASE, LIGHT, SPOT)

    fun of(id: String) = all.firstOrNull { it.id == id }
}
