// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.backend.media3

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper

/** A step of the phone's media volume, and whether the media stream is muted. */
internal data class DeviceLevel(
    val volume: Int,
    val muted: Boolean,
)

/**
 * Reads the phone's media volume when it changes without a volume being set.
 *
 * Media3 reads the volume again when Android reports that a volume was set.
 * Android keeps a media volume for each output, so the level also changes when
 * the music moves to another output, such as from Bluetooth headphones to the
 * speaker, and when the media stream is muted or unmuted. Neither sets a
 * volume, and Media3 goes on reporting the level from before. This reads the
 * volume on both, and when one of the app's activities starts, which also
 * catches a change no broadcast reported.
 *
 * It sets a volume only through [set], for a step the listener asked for.
 */
internal class DeviceVolumeWatch(
    context: Context,
    /** Gets each reading, on the main thread. */
    private val heard: (DeviceLevel) -> Unit,
) {
    private val app = context.applicationContext
    private val audio: AudioManager? = app.getSystemService(AudioManager::class.java)
    private val main = Handler(Looper.getMainLooper())

    private val read = Runnable { reading()?.let(heard) }

    private val changes =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context,
                intent: Intent,
            ) = readSoon()
        }

    private val activities =
        object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) = readSoon()

            override fun onActivityCreated(
                activity: Activity,
                state: Bundle?,
            ) = Unit

            override fun onActivityResumed(activity: Activity) = Unit

            override fun onActivityPaused(activity: Activity) = Unit

            override fun onActivityStopped(activity: Activity) = Unit

            override fun onActivitySaveInstanceState(
                activity: Activity,
                state: Bundle,
            ) = Unit

            override fun onActivityDestroyed(activity: Activity) = Unit
        }

    init {
        val filter =
            IntentFilter().apply {
                addAction(STREAM_DEVICES_CHANGED_ACTION)
                addAction(STREAM_MUTE_CHANGED_ACTION)
            }
        // system broadcasts, so a receiver that is not exported still receives them
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            app.registerReceiver(changes, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            app.registerReceiver(changes, filter)
        }
        (app as? Application)?.registerActivityLifecycleCallbacks(activities)
    }

    /**
     * Reads once for a run of broadcasts: a move to another output sends one
     * for each stream.
     */
    private fun readSoon() {
        main.removeCallbacks(read)
        main.post(read)
    }

    /** The media stream's volume now, or null when the phone does not answer. */
    private fun reading(): DeviceLevel? {
        val audio = audio ?: return null
        // the player plays as media, so the media stream is the one it is heard on
        return runCatching {
            DeviceLevel(
                volume = audio.getStreamVolume(AudioManager.STREAM_MUSIC),
                muted = audio.isStreamMute(AudioManager.STREAM_MUSIC),
            )
        }.getOrNull()
    }

    /**
     * Sets the media stream to [volume] and returns the volume read after,
     * or null when the phone does not answer. A volume above 0 also unmutes
     * the stream, as for any other app that sets one.
     */
    fun set(volume: Int): DeviceLevel? {
        val audio = audio ?: return null
        runCatching { audio.setStreamVolume(AudioManager.STREAM_MUSIC, volume, 0) }
        return reading()
    }

    fun release() {
        main.removeCallbacks(read)
        runCatching { app.unregisterReceiver(changes) }
        (app as? Application)?.unregisterActivityLifecycleCallbacks(activities)
    }

    private companion object {
        /** AudioManager's, left out of the SDK: the outputs a stream plays on changed. */
        const val STREAM_DEVICES_CHANGED_ACTION = "android.media.STREAM_DEVICES_CHANGED_ACTION"

        /** AudioManager's, left out of the SDK: a stream was muted or unmuted. */
        const val STREAM_MUTE_CHANGED_ACTION = "android.media.STREAM_MUTE_CHANGED_ACTION"
    }
}
