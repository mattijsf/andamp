// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.widget

import android.content.Context
import android.util.Log
import nl.mattix.andamp.core.player.PlayerFacade
import nl.mattix.andamp.state.PlaybackRoot
import nl.mattix.andamp.state.TransportStore

/**
 * Routes a press from the home screen to playback.
 *
 * A press is a verb on the [PlayerFacade], the app's own contract, so a widget Stop is the same
 * Stop as the button in the player's window, whichever backend is behind it. With no player
 * attached, [press] builds one through [coldStart]. No media key event is sent: it would drive one
 * backend's session past the facade and leave the app's own state unchanged.
 */
object WidgetControl {
    private const val TAG = "WidgetControl"

    @Volatile
    private var player: PlayerFacade? = null

    /** How a press with no player gets one: the composition root, unless a test replaces it. */
    internal var coldStart: (Context) -> PlayerFacade? = { PlaybackRoot.facade(it) }

    fun attach(facade: PlayerFacade) {
        player = facade
    }

    /** Whether this process has a player attached. */
    val attached: Boolean get() = player != null

    /**
     * Lets go of the player when its owner does, so a later press goes to [coldStart] or the store
     * and never to a player nobody owns.
     */
    fun detach() {
        player = null
    }

    /**
     * A press, routed by what is there to take it. Shuffle and repeat carry no verb and go to
     * [setShuffle] and [setRepeat].
     */
    fun press(
        context: Context,
        button: WidgetButton,
        target: Boolean,
    ) {
        val command = button.command
        if (command == null) {
            when (button) {
                WidgetButton.SHUFFLE -> setShuffle(context, target)
                WidgetButton.REPEAT -> setRepeat(context, target)
                else -> Unit
            }
            return
        }
        // with no player attached this builds one
        val playing =
            player ?: runCatching { coldStart(context) }
                // a receiver must not take the process down, so the failure is logged
                .onFailure { Log.w(TAG, "could not build a player for $command", it) }
                .getOrNull()
        if (playing == null) {
            Log.w(TAG, "no player would take a $command from the widget")
            return
        }
        playing.obey(command)
    }

    /** Where in the track, 0..1. Dropped when no player is attached. */
    fun seekToFraction(fraction: Float) {
        player?.seekToFraction(fraction.coerceIn(0f, 1f))
    }

    fun setVolume(fraction: Float) {
        player?.setVolume(fraction.coerceIn(0f, 1f))
    }

    /**
     * The two flags, set rather than toggled.
     *
     * What to set is decided where the widget was drawn, from the state it was drawn with: a
     * receiver waking in a fresh process has no state to toggle against.
     *
     * With no player the press is not dropped. It goes to [TransportStore], which the app reads at
     * launch, and the widget's snapshot is rewritten so the lamp lights at once.
     */
    fun setShuffle(
        context: Context,
        on: Boolean,
    ) {
        val playing = player
        if (playing != null) {
            playing.setShuffle(on)
            return
        }
        remember(context) { it.copy(shuffle = on) }
        show(context) { it.copy(shuffle = on) }
    }

    fun setRepeat(
        context: Context,
        on: Boolean,
    ) {
        val playing = player
        if (playing != null) {
            playing.setRepeat(on)
            return
        }
        remember(context) { it.copy(repeat = on) }
        show(context) { it.copy(repeat = on) }
    }

    private fun remember(
        context: Context,
        change: (nl.mattix.andamp.state.TransportState) -> nl.mattix.andamp.state.TransportState,
    ) {
        val store = TransportStore(context)
        store.saveSettings(change(store.load()))
    }

    /** With no player there is nobody to write the widget's picture, so the press writes it. */
    private fun show(
        context: Context,
        change: (WidgetSnapshot) -> WidgetSnapshot,
    ) {
        // writing re-stamps the snapshot's age, so an expired claim to be playing is read as
        // expired before the write; otherwise the press would revive Playing for another minute
        val known = WidgetSnapshot.read(context).asKnown(android.os.SystemClock.elapsedRealtime(), attached)
        WidgetSnapshot.write(context, change(known))
        MainWindowWidget.refresh(context)
    }
}
