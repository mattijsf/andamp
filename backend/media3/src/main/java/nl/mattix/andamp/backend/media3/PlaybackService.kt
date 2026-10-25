// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.backend.media3

import android.content.pm.ServiceInfo
import android.os.Build
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession

/**
 * Keeps playback alive in the background: Media3 turns the session into a
 * foreground service with a media-style notification. Started by
 * [Media3PlaybackHost] when playback begins.
 *
 * It is also where a browser finds the playlist. A car binds to it to list
 * the rows and to drive the player, and may be the first thing in the process
 * to ask for the session; see [Media3PlaybackHost.wake].
 */
class PlaybackService : MediaLibraryService() {
    override fun onCreate() {
        super.onCreate()
        // the app's icon in the notification, where Media3 would otherwise put
        // its default. Set before the session is added, since the notification
        // goes up with it
        Media3PlaybackHost.notificationIcon.takeIf { it != 0 }?.let { icon ->
            setMediaNotificationProvider(
                androidx.media3.session.DefaultMediaNotificationProvider
                    .Builder(this)
                    .build()
                    .apply { setSmallIcon(icon) },
            )
        }
        Media3PlaybackHost.service = this
        // register the session eagerly: onGetSession only fires for connecting
        // controllers, and without registration the service posts no notification
        Media3PlaybackHost.session(this)?.let(::addSession)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? =
        Media3PlaybackHost.session(this)

    override fun onDestroy() {
        Media3PlaybackHost.releaseFromService()
        super.onDestroy()
    }

    /**
     * Puts this service in the foreground when it runs in the background, so
     * that the play which follows may take the audio focus.
     *
     * From Android 15 the audio focus is refused to an app that is neither
     * the app in front nor running a foreground service, and the player then
     * stays paused. The floating player is neither: the app behind it is the
     * one in front, and [Media3PlaybackHost.ensureService] starts this service
     * in the background. Media3 puts the service in the foreground once the
     * player plays, which is after the player has asked for the focus. Asked
     * here, it does so for the player as it is, stopped or paused, with the
     * media notification; called on the main thread, as the app's presses
     * are, that is done before this returns.
     *
     * Before Android 15 the focus has no such rule, and nothing is done.
     */
    @androidx.annotation.OptIn(markerClass = [UnstableApi::class])
    internal fun comeForward() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return
        if (foregroundServiceType != ServiceInfo.FOREGROUND_SERVICE_TYPE_NONE) return
        val session = sessions.firstOrNull() ?: return
        onUpdateNotificationAsync(session, true)
    }
}
