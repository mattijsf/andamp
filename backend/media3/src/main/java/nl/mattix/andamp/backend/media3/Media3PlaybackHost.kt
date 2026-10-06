// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.backend.media3

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.annotation.VisibleForTesting
import androidx.media3.common.Player
import androidx.media3.session.MediaSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import nl.mattix.andamp.core.model.Track
import nl.mattix.andamp.core.playback.PlaybackBackend

/**
 * Process-wide owner of the player, backend and media session. Playback
 * outlives the activity, so the backend is not scoped to a ViewModel: the UI
 * borrows it via [backend], and [PlaybackService] borrows the session via
 * [session]. The host starts the service the first time playback begins.
 */
object Media3PlaybackHost {
    private var appContext: Context? = null
    private var scope: CoroutineScope? = null
    private var playerInstance: Player? = null
    private var backendInstance: Media3Backend? = null
    private var sessionInstance: MediaSession? = null
    private var serviceStarted = false

    /**
     * The listener that keeps the session's shuffle and repeat buttons
     * current, and the player it is on.
     *
     * Kept so it can be removed when the session is released: the player
     * lives for the process and a session does not.
     */
    private var layoutListener: Player.Listener? = null
    private var layoutPlayer: Player? = null

    /**
     * The icon of the media notification, set by the app, whose resources hold
     * the drawable. Zero leaves Media3's default icon.
     */
    @androidx.annotation.DrawableRes
    var notificationIcon: Int = 0

    /**
     * Which uri a local file is opened under right now, given the uri its row carries. Set
     * by the app, which knows what it may read: a row keeps the address it was added under,
     * and the same file can be reachable another way when that address no longer opens.
     *
     * Everything in this module that opens a row's file asks here at the moment it opens
     * it: playback, the kbps readout and the cover. The answer is used for that one read
     * and is not kept, so the row itself never changes. Called off the main thread.
     */
    @Volatile
    var readableUri: (android.net.Uri) -> android.net.Uri = { it }

    fun backend(
        context: Context,
        initialTracks: List<Track>,
        startIndex: Int = 0,
    ): Media3Backend {
        backendInstance?.let { return it }
        val app = context.applicationContext
        appContext = app
        val hostScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        scope = hostScope
        val backend = Media3Backend(app, initialTracks, hostScope, startIndex)
        backendInstance = backend
        playerInstance = backend.playerForSession
        // start the foreground service the first time audio starts, so
        // playback survives the activity going away
        promoteWhenPlaying(app, backend.playerForSession)
        return backend
    }

    /**
     * Hosts a backend this module did not build, so it gets the same session,
     * service and notification.
     *
     * A `MediaSession` needs a `Player`, and [BackendPlayer] adapts any
     * [PlaybackBackend] to one.
     */
    fun host(
        context: Context,
        backend: nl.mattix.andamp.core.playback.PlaybackBackend,
    ) {
        val app = context.applicationContext
        appContext = app
        val hostScope = scope ?: CoroutineScope(SupervisorJob() + Dispatchers.Main).also { scope = it }
        val player = BackendPlayer(backend, hostScope)
        playerInstance = player
        promoteWhenPlaying(app, player)
    }

    /**
     * Starts the service the first time audio starts, and not before: a
     * foreground service with nothing playing would show a notification the
     * listener did not ask for.
     */
    private fun promoteWhenPlaying(
        app: Context,
        player: Player,
    ) {
        player.addListener(
            object : Player.Listener {
                override fun onPlayWhenReadyChanged(
                    playWhenReady: Boolean,
                    reason: Int,
                ) {
                    if (playWhenReady && !serviceStarted) {
                        serviceStarted = true
                        startPlaying(app)
                    }
                }
            },
        )
    }

    /** Called by [PlaybackService]. Null until [backend] or [host] has set a player. */
    internal fun session(context: Context): MediaSession? {
        sessionInstance?.let { return it }
        val player = playerInstance ?: return null
        val app = context.applicationContext
        return MediaSession
            .Builder(app, player)
            // the cover on the notification and the lock screen. Wrapped in
            // Media3's cache because the same cover is asked for repeatedly,
            // and reading a file's tags is slow
            .setBitmapLoader(androidx.media3.session.CacheBitmapLoader(CoverBitmapLoader(app)))
            .apply {
                // what tapping the notification opens. The launch intent is
                // used because this module cannot see the app's activities.
                app.packageManager.getLaunchIntentForPackage(app.packageName)?.let { launch ->
                    setSessionActivity(
                        PendingIntent.getActivity(app, 0, launch, PendingIntent.FLAG_IMMUTABLE),
                    )
                }
            }.setCallback(TransportExtras(player))
            .build()
            .also { session ->
                sessionInstance = session
                session.setCustomLayout(shuffleAndRepeat(player))
                // the buttons show the player's state, so they follow the
                // player and not only the presses that arrive through them
                val layout =
                    object : Player.Listener {
                        override fun onShuffleModeEnabledChanged(enabled: Boolean) {
                            session.setCustomLayout(shuffleAndRepeat(player))
                        }

                        override fun onRepeatModeChanged(mode: Int) {
                            session.setCustomLayout(shuffleAndRepeat(player))
                        }
                    }
                player.addListener(layout)
                layoutListener = layout
                layoutPlayer = player
            }
    }

    /** Releases the session and removes the listener that kept its buttons current. */
    private fun letSessionGo() {
        layoutListener?.let { listener -> layoutPlayer?.removeListener(listener) }
        layoutListener = null
        layoutPlayer = null
        sessionInstance?.release()
        sessionInstance = null
    }

    /**
     * Winamp's shuffle and repeat, on the notification.
     *
     * Media3 draws previous, play and next itself. These two are added, each
     * with an icon for its on and its off state.
     */
    private fun shuffleAndRepeat(player: Player): List<androidx.media3.session.CommandButton> =
        listOf(
            androidx.media3.session.CommandButton
                .Builder(
                    if (player.shuffleModeEnabled) {
                        androidx.media3.session.CommandButton.ICON_SHUFFLE_ON
                    } else {
                        androidx.media3.session.CommandButton.ICON_SHUFFLE_OFF
                    },
                ).setDisplayName("Shuffle")
                .setSessionCommand(androidx.media3.session.SessionCommand(SHUFFLE, android.os.Bundle.EMPTY))
                .build(),
            androidx.media3.session.CommandButton
                .Builder(
                    if (player.repeatMode == Player.REPEAT_MODE_OFF) {
                        androidx.media3.session.CommandButton.ICON_REPEAT_OFF
                    } else {
                        androidx.media3.session.CommandButton.ICON_REPEAT_ALL
                    },
                ).setDisplayName("Repeat")
                .setSessionCommand(androidx.media3.session.SessionCommand(REPEAT, android.os.Bundle.EMPTY))
                .build(),
        )

    /** Grants the two extra commands, and does what they ask. */
    private class TransportExtras(
        private val player: Player,
    ) : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult =
            MediaSession.ConnectionResult
                .AcceptedResultBuilder(session)
                .setAvailableSessionCommands(
                    MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS
                        .buildUpon()
                        .add(androidx.media3.session.SessionCommand(SHUFFLE, android.os.Bundle.EMPTY))
                        .add(androidx.media3.session.SessionCommand(REPEAT, android.os.Bundle.EMPTY))
                        .build(),
                ).build()

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: androidx.media3.session.SessionCommand,
            args: android.os.Bundle,
        ): com.google.common.util.concurrent.ListenableFuture<androidx.media3.session.SessionResult> {
            when (customCommand.customAction) {
                SHUFFLE -> {
                    player.shuffleModeEnabled = !player.shuffleModeEnabled
                }

                REPEAT -> {
                    player.repeatMode =
                        if (player.repeatMode == Player.REPEAT_MODE_OFF) {
                            Player.REPEAT_MODE_ALL
                        } else {
                            Player.REPEAT_MODE_OFF
                        }
                }
            }
            return com.google.common.util.concurrent.Futures.immediateFuture(
                androidx.media3.session.SessionResult(androidx.media3.session.SessionResult.RESULT_SUCCESS),
            )
        }
    }

    private const val SHUFFLE = "nl.mattix.andamp.SHUFFLE"
    private const val REPEAT = "nl.mattix.andamp.REPEAT"

    /**
     * The floating player calls this for as long as it is on screen. A process
     * with no activity, no playback and no service is cached, and Android
     * freezes cached processes, which would freeze the overlay. The session
     * service keeps the process running.
     */
    fun ensureService(context: Context) {
        if (serviceStarted) return
        serviceStarted = true
        val app = context.applicationContext
        // not a foreground start: this runs when the floating player appears,
        // possibly with nothing playing. A foreground start must reach
        // startForeground() within five seconds or the process is killed with
        // ForegroundServiceDidNotStartInTimeException, and Media3 posts no
        // media notification for an idle player.
        start(app, false)
    }

    /**
     * Starts the session service in the foreground, which keeps playback alive
     * once the app is off screen.
     *
     * A plain startService gives a background service, which the system stops
     * once the app leaves the foreground (API 26 and later). Media3 calls
     * startForeground() when the notification goes up, which a playing player
     * produces.
     *
     * A failure is caught and logged, because play can be pressed while the
     * app is in the background, for example from the home screen widget, where
     * the system may refuse the start. The next play tries again.
     */
    private fun startPlaying(app: Context) {
        runCatching { start(app, true) }
            .onFailure {
                serviceStarted = false
                android.util.Log.w("Media3PlaybackHost", "playback service would not start", it)
            }
    }

    /** The call to the platform, replaceable so a test can observe it. */
    internal var start: (Context, Boolean) -> Unit = { app, foreground ->
        val intent = Intent(app, PlaybackService::class.java)
        if (foreground) app.startForegroundService(intent) else app.startService(intent)
    }

    /**
     * Main menu > Exit: tears the media notification down by stopping the
     * session service, whose onDestroy releases the session. The player and
     * backend survive for a relaunch; callers stop playback first.
     */
    fun stopService(context: Context) {
        context.applicationContext.stopService(Intent(context.applicationContext, PlaybackService::class.java))
        serviceStarted = false
    }

    /**
     * The service owns only the session. The backend and player live for the
     * process: the service can end while the UI is still running, and
     * releasing the shared player there would end its audio.
     */
    internal fun releaseFromService() {
        letSessionGo()
        serviceStarted = false // next play starts a fresh service
    }

    /**
     * Clears the process singleton, so each test starts from nothing.
     *
     * Nothing in the app calls this. It releases everything [backend] and
     * [host] set up.
     */
    @VisibleForTesting
    internal fun reset(context: Context) {
        stopService(context)
        letSessionGo()
        // a hosted player is released here; the backend inside it belongs to
        // whoever passed it in
        (playerInstance as? BackendPlayer)?.release()
        backendInstance?.release()
        backendInstance = null
        playerInstance = null
        scope?.cancel()
        scope = null
        appContext = null
        serviceStarted = false
    }
}
