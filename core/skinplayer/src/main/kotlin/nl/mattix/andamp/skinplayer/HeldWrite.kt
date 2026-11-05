// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.skinplayer

import kotlin.math.abs

/**
 * One value a skin writes to a [SkinPlayer], held until the player's state shows it.
 *
 * The state follows a write only once the player reports back, while a skin reads a value back at
 * once and tells its scripts about any change. So a written value reads as written while the state
 * shows what the write becomes there (its image), and for up to [holdMs] while the state still
 * shows a value from before the write. Any other value is a change made elsewhere (the volume
 * keys, a notification) and reads at once.
 *
 * A write lands within [slack] of its image, or on the image once the state has shown it: the
 * first such value that is not from before is where it landed, and only that value holds it from
 * then on. A value from before that is also where the write can land holds it, and after [holdMs]
 * counts as where it landed, since a write can land on the value the state showed. The values from
 * before are what the state showed at each write since the last one landed, and the images of the
 * writes that had not landed, since those can still arrive first, each within [slack].
 */
public class HeldWrite(
    /** How far from its image a write may land: a phone's volume step lands near, not on, a percent. */
    private val slack: Int = 0,
    /** How long a written value outlasts a state that still shows the old one. */
    private val holdMs: Long = HOLD_MS,
    /** The time in ms. */
    private val clock: () -> Long,
) {
    private var active = false
    private var landed = false
    private var shown = false
    private var value = 0
    private var image = 0
    private var at = 0L
    private val before = HashSet<Int>()
    private val earlier = HashSet<Int>()

    /** The skin wrote [value], which the state shows as [image]; the state shows [mirror] now. */
    public fun write(
        value: Int,
        image: Int,
        mirror: Int,
    ) {
        if (active && !landed) {
            earlier += this.image
        } else {
            before.clear()
            earlier.clear()
        }
        earlier -= image
        before += mirror
        this.value = value
        this.image = image
        at = clock()
        active = true
        landed = false
        shown = false
    }

    /** The held value, or the state's [mirror] through [unheld] once the write no longer holds. */
    public fun read(
        mirror: Int,
        unheld: (Int) -> Int,
    ): Int {
        if (active && !holds(mirror)) active = false
        return if (active) value else unheld(mirror)
    }

    /** Whether the write still holds while the state shows [mirror]; notes where it landed. */
    private fun holds(mirror: Int): Boolean {
        if (landed) return mirror == image
        if (mirror == image) shown = true
        val lands = abs(mirror - image) <= if (shown) 0 else slack
        val fromBefore = mirror in before || earlier.any { abs(it - mirror) <= slack }
        val waited = clock() - at
        if (lands && (!fromBefore || waited >= holdMs)) {
            image = mirror
            landed = true
        }
        return lands || (fromBefore && waited < holdMs)
    }

    public companion object {
        /** How long a written value outlasts a state that still shows the old one, by default. */
        public const val HOLD_MS: Long = 1_000L
    }
}
