// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.wal

import nl.mattix.andamp.ui.window.Loupe
import kotlin.math.hypot

/**
 * One finger on a modern skin, as the skin hears it: a press, its moves and its release; a second
 * tap soon after a first one as a double tap; and a long press, which the skin does not hear as a
 * click but which opens the main menu. A press on a part of the skin that is not a control
 * ([WalLayout.moveTarget]) that travels past the touch slop moves the whole stack through [drag]
 * instead: the skin's press is cancelled and it hears nothing more of that finger.
 *
 * A hold on one of the skin's small controls opens a magnifier ([lens], the app's Tap assist): the
 * finger then steers the skin's press with a crosshair, geared down, and the press is precise
 * ([Target.pointerPrecise]), so the skin lights whatever the crosshair is on, and the release lets
 * go there. Pushed far past the controls the lens shows, it closes and the press is cancelled.
 *
 * It keeps no clock and reads no events itself: the host hands it the first finger's events with
 * their times and asks it when the hold or the long press is due ([longPressIn], [due]). Positions are surface pixels from the
 * stack's top-left corner; [toSkin] turns them into skin pixels with the placement of the moment.
 */
internal class WalTouch(
    private val surface: Target,
    private val rules: Rules,
    private val toSkin: (TouchPoint) -> Pair<Int, Int>,
    /** A finger went down at this point, before the skin hears of it. */
    private val onPress: (TouchPoint) -> Unit = {},
    /** The long press fired at the point the finger went down, in the gesture of this seq; the skin's press is cancelled. */
    private val onLongPress: (at: TouchPoint, seq: Int) -> Unit,
    /** What moves the stack, or null where it cannot be moved. */
    private val drag: Drag? = null,
    /** The magnifier a hold opens, or null when Tap assist is off. */
    private val lens: Lens? = null,
) {
    /** Where the skin hears the finger: the engine's pointer calls, in skin px, as a host passes them on. */
    interface Target {
        /** A press at ([x], [y]); returns its seq. */
        fun pointerDown(
            x: Int,
            y: Int,
        ): Int

        /** The press moved to ([x], [y]). */
        fun pointerMove(
            x: Int,
            y: Int,
        )

        /** The press lifted at ([x], [y]). */
        fun pointerUp(
            x: Int,
            y: Int,
        )

        /** The press ended without a release. */
        fun pointerCancel()

        /** A second tap at ([x], [y]); returns its seq, as [pointerDown] does. */
        fun doubleTap(
            x: Int,
            y: Int,
        ): Int

        /**
         * The seq of the down that landed on a part of the skin a drag moves it by, as the latest
         * layout says ([WalLayout.moveTarget]), or null.
         */
        val moveTarget: Int?

        /** The rest of the press lands exactly where it says, as a mouse's does: a magnifier steers it. */
        fun pointerPrecise() {}

        /** The keys held with the next press, from a keyboard beside the finger or mouse: Ctrl and Shift. */
        fun pointerModifiers(
            ctrl: Boolean,
            shift: Boolean,
        ) {}

        /** Hands [onControls] the controls of the window the press is on, in the stack's skin pixels, later on main. */
        fun pressControls(onControls: (List<nl.mattix.walkit.TouchControl>) -> Unit) {}
    }

    /** The magnifier, as the host shows it. */
    interface Lens {
        /** Whether a hold opens it now: Tap assist is on. */
        val enabled: Boolean

        /** Skin pixels per surface pixel now. */
        val skinPerPx: Float

        /** The lens a hold at [at] opens from [controls], or null where the press is on no small control. */
        fun open(
            at: TouchPoint,
            controls: List<nl.mattix.walkit.TouchControl>,
        ): WalLens?

        /** Shows [lens] over the skin, or no lens. */
        fun show(lens: WalLens?)
    }

    /** Moving the whole stack with the finger; positions as [WalTouch] takes them. */
    interface Drag {
        /** The finger that went down at [from] traveled past the touch slop to [at]: the stack follows it from here. */
        fun start(
            from: TouchPoint,
            at: TouchPoint,
        )

        /** The finger moved to [at]. */
        fun move(at: TouchPoint)

        /** The finger lifted, or the gesture ended without a release: the stack stays where it is. */
        fun end()
    }

    /** The platform's gesture thresholds: times in ms, distances in surface pixels. */
    data class Rules(
        val longPressMs: Long,
        val touchSlop: Float,
        val doubleTapMs: Long,
        val doubleTapSlop: Float,
    )

    /** The finger now down. */
    private class Press(
        val at: TouchPoint,
        val time: Long,
        val seq: Int,
        /** The second tap of a double tap, whose release the skin does not hear. */
        val second: Boolean,
    ) {
        /** Still within the touch slop of where it went down, so a long press or a tap. */
        var still = true

        /** A skin menu opened during it, which rules the long press out. */
        var menuSeen = false

        /** The long press fired, so the rest of the finger goes nowhere. */
        var taken = false

        /** It moves the stack, so the rest of the finger goes to [drag]. */
        var dragging = false

        /** The hold that may open the lens has yet to come. */
        var holdAhead = false

        /** The lens it opened, which steers the rest of it. */
        var lens: WalLens? = null

        /** The control the lens opened on, which the skin's press went down on. */
        var lensStart: androidx.compose.ui.unit.IntRect? = null

        /** Where the finger was last seen while the lens is up; null until its first move after the lens opened. */
        var steeredFrom: TouchPoint? = null

        /**
         * Still a hold: not taken, within the slop, no skin menu up and no lens open, so the lens
         * or the long press may come.
         */
        val holding: Boolean get() = !taken && still && !menuSeen && lens == null
    }

    /** A tap that ended at [upTime], which a second tap near [at] makes a double tap. */
    private class Tap(
        val at: TouchPoint,
        val upTime: Long,
    )

    private var press: Press? = null
    private var lastTap: Tap? = null

    /** A finger went down at [at] at [time], with Ctrl and Shift held or not ([ctrl], [shift]). */
    fun down(
        at: TouchPoint,
        time: Long,
        ctrl: Boolean = false,
        shift: Boolean = false,
    ) {
        cancel()
        surface.pointerModifiers(ctrl, shift)
        val tap = lastTap
        lastTap = null
        val second = tap != null && time - tap.upTime <= rules.doubleTapMs && tap.at.distanceTo(at) <= rules.doubleTapSlop
        onPress(at)
        val p = toSkin(at)
        val seq = if (second) surface.doubleTap(p.first, p.second) else surface.pointerDown(p.first, p.second)
        press = Press(at, time, seq, second).also { it.holdAhead = lens?.enabled == true && !second }
    }

    /** The finger moved to [at]. */
    fun move(at: TouchPoint) {
        val p = press ?: return
        if (p.taken) return
        if (p.dragging) {
            drag?.move(at)
            return
        }
        p.lens?.let { return steer(p, it, at) }
        if (p.at.distanceTo(at) > rules.touchSlop) p.still = false
        val d = drag
        // the engine names a press that moves the stack once it has handled it: each move asks again
        if (!p.still && d != null && surface.moveTarget == p.seq) {
            p.dragging = true
            surface.pointerCancel()
            d.start(p.at, at)
            return
        }
        val s = toSkin(at)
        surface.pointerMove(s.first, s.second)
    }

    /** The finger lifted at [at] at [time]; [consumed] when the release came already consumed, as a cancelled gesture's does. */
    fun up(
        at: TouchPoint,
        time: Long,
        consumed: Boolean,
    ) {
        val p = press ?: return
        press = null
        val open = p.lens
        when {
            p.taken -> {
                Unit
            }

            open != null -> {
                lens?.show(null)
                if (consumed) {
                    surface.pointerCancel()
                } else {
                    letGoUnderLens(p, open)
                }
            }

            // the stack lands where the finger lifted, which can be past its last move
            p.dragging -> {
                if (!consumed) drag?.move(at)
                drag?.end()
            }

            consumed -> {
                surface.pointerCancel()
            }

            // as Windows sends it: down, up, double click, and a release nobody acts on
            p.second -> {
                Unit
            }

            else -> {
                val s = toSkin(at)
                surface.pointerUp(s.first, s.second)
                if (p.still) lastTap = Tap(p.at, time)
            }
        }
    }

    /**
     * The finger lifted with [open] up: the press lets go where the crosshair is. A skin takes a
     * press let go over another control than the one it went down on as one that slid off (as a
     * mouse does), so when the crosshair was steered to another control the press goes down on
     * that one afresh: the lens is there to pick among small neighbors.
     */
    private fun letGoUnderLens(
        p: Press,
        open: WalLens,
    ) {
        val (x, y) = open.point
        if (open.target != p.lensStart) {
            surface.pointerCancel()
            surface.pointerDown(x, y)
        }
        surface.pointerUp(x, y)
    }

    /** The gesture ended without a release: its change went missing, or the pointer loop was cancelled. */
    fun cancel() {
        val p = press ?: return
        press = null
        lastTap = null
        if (p.lens != null) lens?.show(null)
        if (p.dragging) {
            drag?.end()
        } else if (!p.taken) {
            surface.pointerCancel()
        }
    }

    /** A skin menu is up while the finger is down. */
    fun menuShown() {
        press?.menuSeen = true
    }

    /** How long after [now] the hold that opens the lens or the long press is due, or null when neither can come. */
    fun longPressIn(now: Long): Long? {
        val p = press ?: return null
        if (!p.holding) return null
        val due = if (p.holdAhead) p.time + Loupe.HOLD_MS else p.time + rules.longPressMs
        return (due - now).coerceAtLeast(0)
    }

    /**
     * The wait [longPressIn] gave ran out at [now]: the hold asks the skin for the press's
     * controls, and opens the lens when the press is on a small one; the long press fires when due.
     */
    fun due(now: Long) {
        val p = press ?: return
        if (p.holdAhead && now >= p.time + Loupe.HOLD_MS) {
            p.holdAhead = false
            if (p.holding) surface.pressControls { controls -> held(p, controls) }
            return
        }
        if (now >= p.time + rules.longPressMs) longPressDue()
    }

    /** The skin listed [controls] for the press [p] held: the lens opens if it is still held, still and on a small one. */
    private fun held(
        p: Press,
        controls: List<nl.mattix.walkit.TouchControl>,
    ) {
        val host = lens ?: return
        if (press !== p || p.dragging || !p.holding) return
        val opened = host.open(p.at, controls) ?: return
        p.lens = opened
        p.lensStart = opened.target
        surface.pointerPrecise()
        opened.point.let { (x, y) -> surface.pointerMove(x, y) }
        host.show(opened)
    }

    /**
     * The finger moved to [at] while [open] is up: the crosshair moves by what it did since the last
     * move, geared down, and the skin's press with it. The first move after the lens opens is
     * measured from itself, so what the finger wandered while the hold timed out does not move it.
     */
    private fun steer(
        p: Press,
        open: WalLens,
        at: TouchPoint,
    ) {
        val from = p.steeredFrom
        p.steeredFrom = at
        if (from == null) return
        val perPx = lens?.skinPerPx ?: return
        if (!open.move(
                androidx.compose.ui.geometry
                    .Offset((at.x - from.x) * perPx, (at.y - from.y) * perPx),
            )
        ) {
            // pushed away: the lens closes and nothing is pressed
            p.lens = null
            p.taken = true
            lens.show(null)
            surface.pointerCancel()
            return
        }
        open.point.let { (x, y) -> surface.pointerMove(x, y) }
    }

    /** The long press is due: it fires unless the finger traveled or a skin menu opened since it went down. */
    fun longPressDue() {
        val p = press ?: return
        if (!p.holding) return
        p.taken = true
        surface.pointerCancel()
        onLongPress(p.at, p.seq)
    }
}

/** A finger's place, in a host's surface pixels from the stack's top-left corner. */
internal data class TouchPoint(
    val x: Float,
    val y: Float,
) {
    /** How far [other] lies from this point. */
    fun distanceTo(other: TouchPoint): Float = hypot(other.x - x, other.y - y)
}
