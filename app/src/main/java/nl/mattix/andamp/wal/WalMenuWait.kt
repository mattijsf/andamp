// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.wal

import android.util.Log
import nl.mattix.andamp.modernskin.skin.SkinMenu
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Hands a skin's popup menu from the engine thread to the screen and waits for the pick. The
 * script that popped the menu waits for its answer, so the engine thread blocks in [choose] until
 * the screen calls [answer], the menu is dismissed, the wait runs out or the skin closes. The
 * listener answers a command id, or 0 for none; the other three close the menu without the
 * listener and answer null. The skin stands still meanwhile.
 *
 * [onChange] runs on the engine thread when a menu opens or closes, so the screen redraws.
 */
internal class WalMenuWait(
    private val timeoutMs: Long = TIMEOUT_MS,
    /** Where a menu that ran out its wait is noted, one line each, so a run can count them. */
    private val log: (String) -> Unit = { Log.i(WalHost.TAG, it) },
) {
    @Volatile
    var onChange: (() -> Unit)? = null

    /** Runs on the engine thread just before a menu shows, so the screen has the skin as it stands. */
    @Volatile
    var beforeShow: (() -> Unit)? = null

    /** Nobody is there to pick (a check): every menu answers 0 at once, without showing. */
    @Volatile
    var unattended = false

    /** An answer as it waits in the queue: a command id, 0, or null for a menu closed without the listener. */
    private class Answer(
        val id: Int?,
    )

    private val lock = Any()
    private val answers = LinkedBlockingQueue<Answer>()

    // written under [lock]; read without it too, so the screen never waits on the engine
    @Volatile
    private var open: SkinMenu? = null

    @Volatile
    private var closed = false

    /** The menu waiting for a pick, or null. */
    val menu: SkinMenu? get() = open

    /** Shows [menu] and waits for the answer: a command id, 0, or null. Called on the engine thread. */
    fun choose(menu: SkinMenu): Int? {
        if (unattended) return 0
        // a menu opened while another waits (a script run by the redraw below) is not shown
        if (closed || open != null) return null
        beforeShow?.invoke()
        synchronized(lock) {
            if (closed || open != null) return null
            // an answer left from an earlier menu is not this one's
            answers.clear()
            open = menu
        }
        onChange?.invoke()
        val id =
            try {
                val answer = answers.poll(timeoutMs, TimeUnit.MILLISECONDS)
                if (answer == null) log("menu closed after $timeoutMs ms without an answer")
                answer?.id
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                null
            }
        synchronized(lock) { open = null }
        onChange?.invoke()
        return id
    }

    /** The listener picked [id] from [menu], or 0 for nothing; ignored unless [menu] is the one waiting. */
    fun answer(
        menu: SkinMenu,
        id: Int,
    ) {
        synchronized(lock) { if (open === menu) answers.offer(Answer(id)) }
    }

    /** Closes the menu waiting now, if any, without the listener (the app leaves the screen). */
    fun dismiss() {
        synchronized(lock) { if (open != null) answers.offer(Answer(null)) }
    }

    /** The skin goes away: the menu waiting now closes, and later ones answer at once without showing. */
    fun close() {
        synchronized(lock) {
            closed = true
            answers.offer(Answer(null))
        }
    }

    private companion object {
        /** A menu left open this long closes without an answer, so a forgotten one cannot hold the skin. */
        const val TIMEOUT_MS = 60_000L
    }
}
