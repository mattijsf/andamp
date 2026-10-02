// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.backend.media3

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import nl.mattix.andamp.core.playback.AudioOut

/**
 * What a player owes the rest of the phone while it is making sound.
 *
 * ExoPlayer does this itself for a local file. A backend that renders its own
 * samples through [PcmAudioOut] gets it from here. From start or resume until
 * pause or stop, this:
 * - holds the audio focus and reports its loss, for good or for a moment, and
 *   its return. The owner does the pausing, so the transport state matches
 *   what is heard;
 * - listens for the headphones coming out, and reports that as a loss for good;
 * - keeps the CPU and the Wi-Fi radio awake, as ExoPlayer's network wake mode
 *   does for a stream.
 *
 * A duck (a navigation prompt, a notification) is not reported: the level
 * drops to a fifth and comes back, and nothing pauses.
 */
internal class AudioManners(
    context: Context,
    /** Puts the level on the device again, because a duck has started or ended. */
    private val relevel: () -> Unit,
) {
    private val app = context.applicationContext
    private val audio: AudioManager? = app.getSystemService(AudioManager::class.java)
    private val main = Handler(Looper.getMainLooper())

    /** Called with each interruption, on the main thread. */
    @Volatile var listener: ((AudioOut.Interruption) -> Unit)? = null

    @Volatile private var ducked = false

    /** What the listener's level is multiplied by: 1, or [DUCKED] during a duck. */
    val scale: Float get() = if (ducked) DUCKED else 1f

    private val request =
        AudioFocusRequest
            .Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(musicAttributes())
            .setOnAudioFocusChangeListener({ change -> focusChanged(change) }, main)
            .build()

    /** The audio focus is held right now. */
    private var holding = false

    /** Our request is in the system's line: held, or lost for a moment and waiting to be handed back. */
    private var requested = false

    /** Lost for a moment, and the owner is owed a [AudioOut.Interruption.RESUME] when it comes back. */
    private var pausedForNow = false

    private var listening = false

    private val noisy =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context,
                intent: Intent,
            ) {
                if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) listener?.invoke(AudioOut.Interruption.PAUSE)
            }
        }

    private val wake: PowerManager.WakeLock? =
        app
            .getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, LOCK_TAG)
            ?.apply { setReferenceCounted(false) }

    // WIFI_MODE_FULL_HIGH_PERF is deprecated; it is the lock ExoPlayer's network
    // wake mode takes, and older Android releases still honor it
    @Suppress("DEPRECATION")
    private val wifi: WifiManager.WifiLock? =
        app
            .getSystemService(WifiManager::class.java)
            ?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, LOCK_TAG)
            ?.apply { setReferenceCounted(false) }

    /** Audio is about to be heard: a start, or a resume. */
    @Synchronized
    fun sounding() {
        // a start cancels a resume owed after a transient loss
        pausedForNow = false
        listen(true)
        awake(true)
        if (holding) return
        // with no audio service there is no focus to ask for, so it counts as granted
        val answer = audio?.requestAudioFocus(request) ?: AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        val granted = answer == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        holding = granted
        if (granted) requested = true
        // refused, as during a call: the owner pauses, as for a loss. Posted,
        // because this runs inside the owner's own call
        if (!granted) main.post { listener?.invoke(AudioOut.Interruption.PAUSE) }
    }

    /**
     * Audio is held silent: a pause.
     *
     * A pause caused by a transient loss keeps the focus request, so the focus
     * can be handed back when the loss ends.
     */
    @Synchronized
    fun silent() {
        listen(false)
        awake(false)
        if (!pausedForNow) letGo()
    }

    /** Audio is over: a stop. Abandons the focus and any owed resume. */
    @Synchronized
    fun done() {
        pausedForNow = false
        ducked = false
        listen(false)
        awake(false)
        letGo()
    }

    /** Runs on the main thread, on the handler the request names. */
    private fun focusChanged(change: Int) {
        val wasDucked = ducked
        val say = synchronized(this) { meaningOf(change) }
        if (ducked != wasDucked) relevel()
        say?.let { listener?.invoke(it) }
    }

    /** What a change of focus means for the owner, if anything. */
    private fun meaningOf(change: Int): AudioOut.Interruption? =
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                holding = true
                requested = true
                ducked = false
                resumeIfOwed()
            }

            // for good: nothing will be handed back, so nothing is waited for
            AudioManager.AUDIOFOCUS_LOSS -> {
                pausedForNow = false
                ducked = false
                letGo()
                AudioOut.Interruption.PAUSE
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                holding = false
                pausedForNow = true
                AudioOut.Interruption.PAUSE_FOR_NOW
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                ducked = true
                null
            }

            else -> {
                null
            }
        }

    private fun resumeIfOwed(): AudioOut.Interruption? {
        if (!pausedForNow) return null
        pausedForNow = false
        return AudioOut.Interruption.RESUME
    }

    private fun letGo() {
        if (requested) audio?.abandonAudioFocusRequest(request)
        requested = false
        holding = false
    }

    private fun listen(on: Boolean) {
        if (on == listening) return
        listening = on
        if (!on) {
            runCatching { app.unregisterReceiver(noisy) }
            return
        }
        // a system broadcast, so a receiver that is not exported still receives it
        val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            app.registerReceiver(noisy, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            app.registerReceiver(noisy, filter)
        }
    }

    // released by pause and stop, where the audio ends; no timeout fits a
    // track of unknown length
    @SuppressLint("WakelockTimeout")
    private fun awake(on: Boolean) {
        // a start and the resume that follows it both land here; each lock is
        // taken once
        if (on) {
            wake?.takeUnless { it.isHeld }?.acquire()
            wifi?.takeUnless { it.isHeld }?.acquire()
        } else {
            wake?.takeIf { it.isHeld }?.release()
            wifi?.takeIf { it.isHeld }?.release()
        }
    }

    private companion object {
        /** The level Media3 ducks to as well. */
        const val DUCKED = 0.2f

        const val LOCK_TAG = "AndAmp:pcm-out"
    }
}

/**
 * Music, played for the listener: what the device is opened as and what the
 * focus is asked for as, which have to agree for the phone to treat the two as
 * one player.
 */
internal fun musicAttributes(): AudioAttributes =
    AudioAttributes
        .Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()
