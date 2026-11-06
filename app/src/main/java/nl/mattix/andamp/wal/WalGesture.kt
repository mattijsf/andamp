// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.wal

import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.positionChanged

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
    touch.down(down.position.toTouch(), down.uptimeMillis)
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
            touch.up(change.position.toTouch(), now, consumed = change.isConsumed)
            change.consume()
            return
        }
        if (change.positionChanged()) {
            change.consume()
            touch.move(change.position.toTouch())
        }
    }
}

/** The next pointer event, or null when [waitMs] pass first; with no wait given, it waits as long as it takes. */
private suspend fun AwaitPointerEventScope.nextEvent(waitMs: Long?): PointerEvent? =
    if (waitMs == null) awaitPointerEvent() else withTimeoutOrNull(waitMs) { awaitPointerEvent() }
