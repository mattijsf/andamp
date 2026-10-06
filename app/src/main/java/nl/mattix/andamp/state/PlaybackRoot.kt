// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import nl.mattix.andamp.backend.media3.Media3PlaybackHost
import nl.mattix.andamp.core.playback.PlaybackBackend
import nl.mattix.andamp.core.player.MixedQueueBackend
import nl.mattix.andamp.core.player.PlayerFacade
import nl.mattix.andamp.widget.WidgetOps

/**
 * Builds this process's player for whoever asks first: a window's view model or the home
 * screen widget. It is the composition root, the one place that names a backend.
 *
 * [Media3PlaybackHost] keeps the phone's player and the session. The player is always a
 * [MixedQueueBackend] over every source the phone has; [SourceLanes] follows which sources
 * those are.
 */
object PlaybackRoot {
    /**
     * The player, built if this process has none.
     *
     * The stored playlist is handed over at construction, because the host builds once
     * per process and a later setQueue would replace a queue that is already playing after
     * an activity restart.
     *
     * It is always the composite, even with no source but the phone, so a pack installed
     * later finds one: the view model, the widget's mirror and the media session each hold
     * the player they were given, and it cannot be swapped under them. A playlist of only
     * the phone's rows is one run handed to ExoPlayer whole, which keeps its own advance,
     * gapless playback, shuffle and repeat.
     *
     * The media session drives the composite, through [Media3PlaybackHost.host].
     *
     * Called again once built, it returns the same player and starts the widget's mirror
     * again, which [stopMirroring] may have stopped.
     */
    fun backend(context: Context): PlaybackBackend {
        built?.let {
            mirror?.start()
            return it
        }
        val app = context.applicationContext
        val restored = PlaylistStore(app).initial(DefaultTracks.tracks)
        // the notification uses the app's own icon; set here because the drawable is the app's
        Media3PlaybackHost.notificationIcon = nl.mattix.andamp.R.drawable.ic_launcher_monochrome
        // what the player opens a row's file under is worked out here, where grants and the
        // library are known; see ReadableUri
        val readable = ReadableUri(app)
        Media3PlaybackHost.readableUri = { uri -> android.net.Uri.parse(readable.of(uri.toString())) }
        val player = mixed(app, restored)
        // the host owns the media session, and with it the notification, the lock screen,
        // the headset button and the foreground service. It is given the composite, so the
        // session covers every source's rows and not only the phone's
        Media3PlaybackHost.host(app, player)
        built = player
        resume(app, player)
        startMirroring(app, player)
        return player
    }

    /**
     * Restores shuffle and repeat. Done here, where the player is built, because a press
     * on the home screen widget builds a player with no window.
     */
    private fun resume(
        app: Context,
        player: PlaybackBackend,
    ) {
        val left = TransportStore(app).load()
        val facade = PlayerFacade(player)
        facade.setShuffle(left.shuffle)
        facade.setRepeat(left.repeat)
    }

    /**
     * The one playlist over every source this phone has.
     *
     * The phone's lane is first, so it is built at once and the rack and the audio tap
     * are read from it; see [SourceLanes.phone]. Every other source's lane is added when
     * the source is found and removed when it goes, and its player is built the first
     * time one of its rows is reached. While nobody is signed in there is no player and
     * its rows are skipped.
     */
    private fun mixed(
        app: Context,
        restored: PlaylistCodec.Saved,
    ): MixedQueueBackend {
        val phone = SourceLanes.phone { Media3PlaybackHost.backend(app, emptyList()) }
        val player = MixedQueueBackend(listOf(phone), restored.tracks, restored.currentIndex, scope)
        lanes = SourceLanes(player) { extra -> extra.backend(app, scope) }.also { it.follow(scope) { PackSources.found } }
        return player
    }

    /**
     * A source's account is signing out: its player is released. Its rows stay in the
     * playlist and are skipped until somebody signs in again.
     */
    fun signedOut(source: MusicSource) {
        lanes?.signedOut(source)
    }

    /**
     * Starts the home screen widget's mirror of the player. It is owned here and not by
     * the view model, so it follows the player when no window exists. A mock backend
     * injected into the view model does not come through here.
     *
     * Built once, with the player; after a stop it is started again by [backend] and
     * [facade].
     */
    private fun startMirroring(
        app: Context,
        backend: PlaybackBackend,
    ) {
        mirror = WidgetOps(app, facadeOver(backend), scope).also { it.start() }
    }

    /**
     * Gives the widget's mirror the window state it draws from (which way the clock
     * counts). It is pushed because the mirror is built before any window exists.
     */
    fun follow(state: nl.mattix.andamp.state.WinampState) {
        mirror?.follow(state)
    }

    /**
     * Stops the widget's mirror when the player is being put away. The handle is kept,
     * and the next call to [backend] or [facade] starts it again.
     */
    fun stopMirroring() {
        mirror?.stop()
    }

    /**
     * A facade over the same player, for callers with no view model (the widget). It goes
     * through [backend] every time, which also restarts the mirror.
     */
    fun facade(context: Context): PlayerFacade = facadeOver(backend(context))

    private fun facadeOver(backend: PlaybackBackend): PlayerFacade =
        synchronized(this) { made ?: PlayerFacade(backend).also { made = it } }

    @Volatile
    private var made: PlayerFacade? = null

    /** The player this process built; one per process. */
    @Volatile
    private var built: PlaybackBackend? = null

    /** The extra sources' lanes in [built], kept so a sign-out can release that source's player. */
    private var lanes: SourceLanes? = null

    private var mirror: WidgetOps? = null

    /** Outlives every window, because the player does. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
}
