// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.wal

import androidx.compose.runtime.IntState
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.layer.GraphicsLayer
import kotlinx.coroutines.sync.Mutex
import nl.mattix.andamp.modernskin.skin.SkinMenu

/** The box a player surface shows a modern skin in, as [WalSurface.fit] takes it. */
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
    /** The stack's width in skin px. */
    val stackW: Int,
    /** The stack's height in skin px. */
    val stackH: Int,
    /** Whether a skin window is open over the main one; Back closes it. */
    val windowOpen: Boolean,
    /** Whether something slides or animates, so the screen asks for a tick every display frame. */
    val animating: Boolean,
    /** The seq of the down whose object has a skin menu, or null; see [WalSurface.pointerDown]. */
    val menuTarget: Int?,
)

/**
 * A worn modern skin as the player screen sees it: the frame to draw, where it sits, and where
 * touches, fits and menu answers go.
 *
 * Every call is made on the main thread and posts its work to the engine thread, so main never
 * waits on the skin and never reads its runtime. [frames], [layout] and [menu] change only on main.
 *
 * The activity and the floating player can each show the skin. Only the one holding [turn]
 * installs [sink], posts fits, asks for ticks, shows the skin's menus and publishes the window rect.
 */
internal interface WalSurface {
    /** Bumped on main after each new frame or menu change; changes close together count once. */
    val frames: IntState

    /** Where the stack sits; snapshot state set on main, null until the first frame. */
    val layout: WalLayout?

    /** The skin's popup menu waiting for an answer, or null; snapshot state set on main. */
    val menu: SkinMenu?

    /** Held by the one player surface that owns [sink], [fit], ticks, menus and the window rect. */
    val turn: Mutex

    /** Where host actions and file requests go, or null to drop them; read and written on main only. */
    var sink: WalSink?

    /**
     * Draws the latest frame with its top-left at [scope]'s origin, at [WalLayout.scale]. At a
     * scale between whole numbers the frame is drawn into [layer] off screen and shrunk to fit.
     */
    fun draw(
        scope: DrawScope,
        layer: GraphicsLayer,
    )

    /** [draw] threw [error]; called on main. It counts toward the three failures in a row that drop the skin. */
    fun drawFailed(error: Throwable)

    /** The box the stack goes in. The first call starts the engine; a fit equal to the last does nothing. */
    fun fit(fit: WalFit)

    /** Asks for one tick of the skin's timers and animations; at most one waits at a time. */
    fun requestTick()

    /**
     * A value that changes when app state the skin shows changes, such as the volume, the track or
     * the library opening. Read on main in a snapshotFlow; each change earns the skin a tick.
     */
    fun wakeKey(): Any

    /**
     * A finger went down at ([x], [y]) in skin px. Returns this gesture's seq; the seqs a look hands
     * out from [pointerDown] and [doubleTap] increase.
     */
    fun pointerDown(
        x: Int,
        y: Int,
    ): Int

    /** The finger moved to ([x], [y]) in skin px; moves close together are merged. */
    fun pointerMove(
        x: Int,
        y: Int,
    )

    /** The finger lifted at ([x], [y]) in skin px. */
    fun pointerUp(
        x: Int,
        y: Int,
    )

    /** The gesture ends with no click: a long press took it, or the pointer went elsewhere. */
    fun pointerCancel()

    /** A second tap at ([x], [y]) in skin px; returns its seq, as [pointerDown] does. */
    fun doubleTap(
        x: Int,
        y: Int,
    ): Int

    /** Right-clicks the object and point gesture [seq] went down on, for its skin menu; a stale seq does nothing. */
    fun rightClick(seq: Int)

    /** Closes the topmost skin window over the main one, as Back does. */
    fun closeTopWindow()

    /**
     * The listener's answer to [menu]: the picked command's [id], or 0 for Back, a tap outside or
     * the menu going away. Ignored unless [menu] is the one waiting.
     */
    fun answerMenu(
        menu: SkinMenu,
        id: Int,
    )

    /**
     * Closes the waiting menu without the listener, when the screen goes away, stops or changes
     * skin. It ends the gesture, so a later popup in the same touch answers 0.
     */
    fun dismissMenu()

    /** Posts a save of the skin's settings to the engine thread. */
    fun save()
}
