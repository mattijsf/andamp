// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.welcome

import nl.mattix.andamp.skin.Skin
import nl.mattix.andamp.state.online.OnlineSkin

/**
 * What the first launch offers: keep Andamp's own skin, drawn live, or browse the museum for a
 * classic one.
 *
 * The classic Winamp skin is not shipped with the app; it is in the museum, whose first tiles are
 * fetched and shown on the second card.
 */
data class WelcomeChoice(
    /** The skin on screen, drawn live on the first card. */
    val skin: Skin? = null,
    /** The museum's first tiles, in the museum's order. */
    val tiles: List<OnlineSkin> = emptyList(),
    /** How many skins the museum holds; 0 while it has not said. */
    val museumCount: Int = 0,
    val onKeep: () -> Unit = {},
    val onBrowse: () -> Unit = {},
)
