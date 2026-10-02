// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Bundle

/**
 * The main player window, on the home screen.
 *
 * It draws the same window the app draws, in the same skin. A box with room gets the whole
 * 275-by-116 player and a letterbox-shaped one gets windowshade; see [WidgetLayout].
 *
 * Everything it shows comes from [WidgetSnapshot] on disk, so it can draw when the app's process is
 * not running.
 *
 * This class only receives broadcasts. A press goes to [WidgetControl], which hands it to the
 * player's facade and builds a player when none is attached; pictures are sent by [WidgetSender].
 */
class MainWindowWidget : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        manager: AppWidgetManager,
        ids: IntArray,
    ) {
        // an update broadcast is the launcher asking from scratch, so the whole window is sent
        ids.forEach { WidgetSender.render(context, manager, it, whole = true) }
    }

    /** A resized box may fit a different window or scale, so the whole window is sent. */
    override fun onAppWidgetOptionsChanged(
        context: Context,
        manager: AppWidgetManager,
        id: Int,
        options: Bundle,
    ) {
        WidgetSender.render(context, manager, id, whole = true)
    }

    override fun onDeleted(
        context: Context,
        ids: IntArray,
    ) {
        // drops the removed widgets' send-memos, so a recycled id starts from a whole send
        WidgetSender.forget(ids)
    }

    override fun onDisabled(context: Context) {
        WidgetSender.forgetAll()
    }

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        WidgetIntents.readPress(intent)?.let { (button, target) ->
            WidgetFlash.flash(WidgetFlash.idOf(button)) { refresh(context) }
            WidgetControl.press(context, button, target)
            holdUntilSent()
            return
        }
        WidgetIntents.readSlide(intent)?.let { (slider, fraction) ->
            WidgetFlash.flash(WidgetFlash.idOf(slider)) { refresh(context) }
            when (slider) {
                WidgetSlider.SEEK -> WidgetControl.seekToFraction(fraction)
                WidgetSlider.VOLUME -> WidgetControl.setVolume(fraction)
            }
            holdUntilSent()
            return
        }
        super.onReceive(context, intent)
        holdUntilSent()
    }

    /**
     * Keeps the broadcast open until everything it queued has been sent.
     *
     * The rendering runs on [WidgetSender]'s executor after onReceive returns, and a process
     * cold-started by a broadcast may be killed once it does. goAsync marks the work as still
     * going; the executor runs in order, so a task queued behind the renders finishes the broadcast
     * when they are done.
     */
    private fun holdUntilSent() {
        val pending = goAsync()
        WidgetSender.afterQueue { pending.finish() }
    }

    companion object {
        /** Whether any widget is placed. */
        fun hasInstances(context: Context) = WidgetSender.hasInstances(context)

        /** Redraws every placed widget. */
        fun refresh(context: Context) = WidgetSender.refresh(context)
    }
}
