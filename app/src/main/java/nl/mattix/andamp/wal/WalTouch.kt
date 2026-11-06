// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.wal

import kotlin.math.hypot

/**
 * One finger on a modern skin, as the skin hears it: a press, its moves and its release; a second
 * tap soon after a first one as a double tap; and a long press, which the skin does not hear as a
 * click but which opens the main menu. A press on a part of the skin that is not a control
 * ([WalLayout.moveTarget]) that travels past the touch slop moves the whole stack through [drag]
 * instead: the skin's press is cancelled and it hears nothing more of that finger.
 *
 * It keeps no clock and reads no events itself: the host hands it the first finger's events with
 * their times and asks it when the long press is due ([longPressIn]). Positions are surface pixels from the
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
    }

    /** A tap that ended at [upTime], which a second tap near [at] makes a double tap. */
    private class Tap(
        val at: TouchPoint,
        val upTime: Long,
    )

    private var press: Press? = null
    private var lastTap: Tap? = null

    /** A finger went down at [at] at [time]. */
    fun down(
        at: TouchPoint,
        time: Long,
    ) {
        cancel()
        val tap = lastTap
        lastTap = null
        val second = tap != null && time - tap.upTime <= rules.doubleTapMs && tap.at.distanceTo(at) <= rules.doubleTapSlop
        onPress(at)
        val p = toSkin(at)
        val seq = if (second) surface.doubleTap(p.first, p.second) else surface.pointerDown(p.first, p.second)
        press = Press(at, time, seq, second)
    }

    /** The finger moved to [at]. */
    fun move(at: TouchPoint) {
        val p = press ?: return
        if (p.taken) return
        if (p.dragging) {
            drag?.move(at)
            return
        }
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
        when {
            p.taken -> {
                Unit
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

    /** The gesture ended without a release: its change went missing, or the pointer loop was cancelled. */
    fun cancel() {
        val p = press ?: return
        press = null
        lastTap = null
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

    /** How long after [now] the long press is due, or null when it cannot fire. */
    fun longPressIn(now: Long): Long? {
        val p = press ?: return null
        if (p.taken || !p.still || p.menuSeen) return null
        return (p.time + rules.longPressMs - now).coerceAtLeast(0)
    }

    /** The long press is due: it fires unless the finger traveled or a skin menu opened since it went down. */
    fun longPressDue() {
        val p = press ?: return
        if (p.taken || !p.still || p.menuSeen) return
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
