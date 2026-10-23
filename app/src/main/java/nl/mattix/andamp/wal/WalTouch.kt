// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.wal

import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.unit.IntOffset

/**
 * One finger on a modern skin, as the skin hears it: a press, its moves and its release; a second
 * tap soon after a first one as a double tap; and a long press, which the skin does not hear as a
 * click but which opens the main menu.
 *
 * It keeps no clock and reads no events itself: [walGesture] hands it the first finger's events
 * with their times and asks it when the long press is due. Positions are surface pixels from the
 * stack's top-left corner; [toSkin] turns them into skin pixels with the placement of the moment.
 */
internal class WalTouch(
    private val surface: WalSurface,
    private val rules: Rules,
    private val toSkin: (Offset) -> IntOffset,
    /** A finger went down at this point, before the skin hears of it. */
    private val onPress: (Offset) -> Unit = {},
    /** The long press fired at the point the finger went down, in the gesture of this seq; the skin's press is cancelled. */
    private val onLongPress: (at: Offset, seq: Int) -> Unit,
) {
    /** Android's gesture thresholds: times in ms, distances in surface pixels. */
    data class Rules(
        val longPressMs: Long,
        val touchSlop: Float,
        val doubleTapMs: Long,
        val doubleTapSlop: Float,
    )

    /** The finger now down. */
    private class Press(
        val at: Offset,
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
    }

    /** A tap that ended at [upTime], which a second tap near [at] makes a double tap. */
    private class Tap(
        val at: Offset,
        val upTime: Long,
    )

    private var press: Press? = null
    private var lastTap: Tap? = null

    /** A finger went down at [at] at [time]. */
    fun down(
        at: Offset,
        time: Long,
    ) {
        cancel()
        val tap = lastTap
        lastTap = null
        val second = tap != null && time - tap.upTime <= rules.doubleTapMs && (at - tap.at).getDistance() <= rules.doubleTapSlop
        onPress(at)
        val p = toSkin(at)
        val seq = if (second) surface.doubleTap(p.x, p.y) else surface.pointerDown(p.x, p.y)
        press = Press(at, time, seq, second)
    }

    /** The finger moved to [at]. */
    fun move(at: Offset) {
        val p = press ?: return
        if (p.taken) return
        if ((at - p.at).getDistance() > rules.touchSlop) p.still = false
        val s = toSkin(at)
        surface.pointerMove(s.x, s.y)
    }

    /** The finger lifted at [at] at [time]; [consumed] when the release came already consumed, as a cancelled gesture's does. */
    fun up(
        at: Offset,
        time: Long,
        consumed: Boolean,
    ) {
        val p = press ?: return
        press = null
        when {
            p.taken -> {
                Unit
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
                surface.pointerUp(s.x, s.y)
                if (p.still) lastTap = Tap(p.at, time)
            }
        }
    }

    /** The gesture ended without a release: its change went missing, or the pointer loop was cancelled. */
    fun cancel() {
        val p = press ?: return
        press = null
        lastTap = null
        if (!p.taken) surface.pointerCancel()
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

/**
 * Hands one gesture's first finger to [touch], until it lifts. Other fingers are ignored.
 *
 * Compose ends a gesture it cancels with a release that arrives consumed; that, a finger whose
 * change goes missing and a loop cancelled before the release each cancel the skin's press.
 * [menuUp] says whether a skin menu is up, which rules the long press out.
 */
internal suspend fun AwaitPointerEventScope.walGesture(
    touch: WalTouch,
    menuUp: () -> Boolean,
) {
    val down = awaitFirstDown()
    down.consume()
    touch.down(down.position, down.uptimeMillis)
    try {
        follow(down.id, down.uptimeMillis, touch, menuUp)
    } finally {
        // nothing left to cancel after a release; a loop cut off mid-press cancels it here
        touch.cancel()
    }
}

/** Hands [touch] the finger [id]'s events, from its press at [downAt] until it lifts or goes missing. */
private suspend fun AwaitPointerEventScope.follow(
    id: PointerId,
    downAt: Long,
    touch: WalTouch,
    menuUp: () -> Boolean,
) {
    var now = downAt
    while (true) {
        if (menuUp()) touch.menuShown()
        val event = nextEvent(touch.longPressIn(now))
        if (event == null) {
            // held: the finger is still down
            if (menuUp()) touch.menuShown()
            touch.longPressDue()
            continue
        }
        val change = event.changes.firstOrNull { it.id == id } ?: return touch.cancel()
        now = change.uptimeMillis
        if (change.changedToUpIgnoreConsumed()) {
            touch.up(change.position, now, consumed = change.isConsumed)
            change.consume()
            return
        }
        if (change.positionChanged()) {
            change.consume()
            touch.move(change.position)
        }
    }
}

/** The next pointer event, or null when [waitMs] pass first; with no wait given, it waits as long as it takes. */
private suspend fun AwaitPointerEventScope.nextEvent(waitMs: Long?): PointerEvent? =
    if (waitMs == null) awaitPointerEvent() else withTimeoutOrNull(waitMs) { awaitPointerEvent() }
