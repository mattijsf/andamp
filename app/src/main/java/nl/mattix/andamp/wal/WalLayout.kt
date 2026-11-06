// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.wal

import nl.mattix.walkit.Rect
import nl.mattix.walkit.SkinOptions

/** The box a host shows a modern skin in, as [WalFitting] fits the skin to it. */
internal data class WalFit(
    /** The box's width in device px. */
    val widthPx: Int,
    /** The box's height in device px. */
    val heightPx: Int,
    /** Device px per dp. */
    val density: Float,
    /** Whether the phone is held sideways. */
    val sideways: Boolean,
    /**
     * Whether the stack keeps every window at its natural height, and a window that resizes both
     * ways at its smallest height, instead of filling the box: the floating player, which covers no
     * more of the app behind it than it shows.
     */
    val compact: Boolean = false,
)

/**
 * Where a modern skin's windows sit in a [WalFit] box, worked out on the engine thread for each
 * frame it publishes. Opening a skin window changes the stack's size, so it is worked out again at
 * every publish, from the last fit.
 */
internal data class WalLayout(
    /** The fit this layout was worked out for; a layout for another fit does not match the box on screen. */
    val fit: WalFit,
    /** Device px per skin px: whole at 2x and up, fractional below. */
    val scale: Float,
    /** The stack's left edge inside the fit box, in device px. */
    val left: Float,
    /** The stack's top edge inside the fit box, in device px. */
    val top: Float,
    /**
     * The stack's width in skin px: the part its windows show, without margins that show nothing
     * (`SkinRuntime.drawnSize`).
     */
    val stackW: Int,
    /** The stack's height in skin px, as [stackW] measures it: Winamp Modern's closed drawer is left out. */
    val stackH: Int,
    /** Whether a skin window is open over the main one; Back closes it. */
    val windowOpen: Boolean,
    /** Whether something slides or animates, so a stack changing size may not have come to rest yet. */
    val animating: Boolean,
    /** Whether a target slide runs, which the screen ticks on every display frame ([SkinRuntime.sliding]). */
    val sliding: Boolean = false,
    /** The seq of the down whose object has a skin menu, or null; see [WalTouch.Target.pointerDown]. */
    val menuTarget: Int?,
    /**
     * The seq of the down that landed on a part of the skin a drag moves it by, anywhere but a
     * control (`SkinRuntime.movesWindow`), or null; a drag from it moves the stack.
     */
    val moveTarget: Int? = null,
    /**
     * Whether the screen ticks the skin on every display frame ([WalRest.wantsFrames]); while it
     * does not, the engine wakes the skin itself when it is due.
     */
    val wantsFrames: Boolean = true,
    /**
     * The left column of a stack set out in two columns, in skin px from the stack's top-left
     * corner. The plug-in window docks against it as it does against a whole stack:
     * under it, or over it when there is no room below, from the
     * column's left edge. Null for a stack in one column: the plug-in window docks against the
     * whole stack.
     */
    val dock: Rect? = null,
    /**
     * How many times the skin moved its main window against a screen edge after the listener
     * picked from one of its menus (`SkinRuntime.edgePlacements`), as a corner picked from
     * CornerAmp's corner menu does: each new one puts the stack back where the engine places it,
     * dropping a place the listener moved it to.
     */
    val edgePlacements: Int = 0,
    /**
     * The skin's color themes, in the order the skin's theme list shows them (case-insensitive),
     * one per name ignoring case; empty for a skin with none.
     */
    val themes: List<String> = emptyList(),
    /** The color theme this frame wears, one of [themes], or null for a skin with none. */
    val theme: String? = null,
    /** The options the skin lists for Winamp's Options menu, as of this frame. */
    val skinOptions: SkinOptions = SkinOptions.EMPTY,
    /**
     * Where the skin shows Winamp's visualizer, in skin px from the stack's top-left corner
     * (`SkinRuntime.visRect`), as Winamp Modern's drawer shows it in its visualizer mode; null when
     * it shows none. The player draws its visualizer there.
     */
    val visRect: Rect? = null,
)
