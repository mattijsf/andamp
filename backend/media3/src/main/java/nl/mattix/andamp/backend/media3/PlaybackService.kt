// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.backend.media3

import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * Keeps playback alive in the background: Media3 turns the session into a
 * foreground service with a media-style notification. Started by
 * [Media3PlaybackHost].
 */
class PlaybackService : MediaSessionService() {
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
        // register the session eagerly: onGetSession only fires for connecting
        // controllers, and without registration the service posts no notification
        Media3PlaybackHost.session(this)?.let(::addSession)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = Media3PlaybackHost.session(this)

    override fun onDestroy() {
        Media3PlaybackHost.releaseFromService()
        super.onDestroy()
    }
}
