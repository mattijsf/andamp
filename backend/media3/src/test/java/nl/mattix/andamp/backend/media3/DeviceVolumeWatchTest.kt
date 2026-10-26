// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.backend.media3

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Looper
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.test.utils.FakeMediaSourceFactory
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import nl.mattix.andamp.core.model.Track
import nl.mattix.andamp.core.model.VolumeMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/**
 * The slider follows the phone's media volume through changes Media3 does not
 * read: a move to another output, a mute, and a change that happened while the
 * app was away.
 *
 * Each case sets the volume behind Media3's back, as Android does, and sends
 * the broadcast Android sends for it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DeviceVolumeWatchTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val audio = context.getSystemService(AudioManager::class.java)
    private val steps = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var backend: Media3Backend? = null
    private lateinit var player: ExoPlayer

    /** A backend built as the app's is, with the slider on the phone's volume. */
    private fun backend(): Media3Backend {
        player =
            ExoPlayer
                .Builder(context)
                .setMediaSourceFactory(FakeMediaSourceFactory())
                .setDeviceVolumeControlEnabled(true)
                .build()
        return Media3Backend(listOf(Track("t0", "Artist", "Title", 10_000)), scope, player, volumeContext = context).also {
            backend = it
            idleEverything()
        }
    }

    /**
     * What Android does when the music moves to another output, such as from
     * Bluetooth headphones to the speaker: the media volume becomes that
     * output's, and no volume-changed broadcast is sent.
     */
    private fun moveOutput(volume: Int) {
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, volume, 0)
        context.sendBroadcast(
            Intent("android.media.STREAM_DEVICES_CHANGED_ACTION")
                .putExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", AudioManager.STREAM_MUSIC),
        )
        idleEverything()
    }

    /** What Android does when the media stream is muted or unmuted; no volume is set. */
    private fun mute(muted: Boolean) {
        shadowOf(audio).setIsStreamMute(AudioManager.STREAM_MUSIC, muted)
        context.sendBroadcast(
            Intent("android.media.STREAM_MUTE_CHANGED_ACTION")
                .putExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", AudioManager.STREAM_MUSIC),
        )
        idleEverything()
    }

    /** Media3 keeps the device's volume on a background thread, so the main looper alone never sees it. */
    private fun idleEverything() {
        repeat(2) {
            ShadowLooper
                .getAllLoopers()
                .filter { it.thread.isAlive }
                .forEach {
                    try {
                        shadowOf(it).idle()
                    } catch (quitting: IllegalStateException) {
                        // a looper of an earlier test's player, quitting: there
                        // is nothing left to idle
                        check(quitting.message?.contains("quitting") == true) { "only a quitting looper throws here: $quitting" }
                    }
                }
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    @After
    fun tearDown() {
        backend?.release()
        scope.cancel()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun `the slider follows the volume of the output the music moved to`() {
        val b = backend()
        b.setVolume(1f) // the headphones' volume, at the top
        idleEverything()

        moveOutput(4) // the speaker's volume

        assertEquals("Media3 still holds the headphones' volume", steps, player.deviceVolume)
        assertEquals(4 / steps.toFloat(), b.state.value.volumeFraction, 0.001f)
    }

    @Test
    fun `the slider goes to zero while the media stream is muted, and back after`() {
        val b = backend()
        b.setVolume(0.5f)
        idleEverything()
        val level = b.state.value.volumeFraction

        mute(true)
        assertEquals("muted", 0f, b.state.value.volumeFraction, 0.001f)

        mute(false)
        assertEquals("unmuted", level, b.state.value.volumeFraction, 0.001f)
    }

    @Test
    fun `coming back to the app reads the volume again`() {
        val b = backend()
        b.setVolume(1f)
        idleEverything()
        // a change no broadcast reported
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, 4, 0)
        idleEverything()

        Robolectric.buildActivity(Activity::class.java).setup()
        idleEverything()

        assertEquals(4 / steps.toFloat(), b.state.value.volumeFraction, 0.001f)
    }

    /**
     * Media3 still holds the headphones' volume after the move, and takes a
     * drag to that same step for the one it already has.
     */
    @Test
    fun `dragging to the top after a move to the speaker sets the top`() {
        val b = backend()
        b.setVolume(1f) // the headphones' volume, at the top
        idleEverything()
        moveOutput(4) // the speaker's volume

        b.setVolume(1f)
        idleEverything()

        assertEquals(steps, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
        assertEquals(1f, b.state.value.volumeFraction, 0.001f)
    }

    @Test
    fun `dragging elsewhere after a move to the speaker still goes through Media3`() {
        val b = backend()
        b.setVolume(1f)
        idleEverything()
        moveOutput(4)

        b.setVolume(10 / steps.toFloat())
        idleEverything()

        assertEquals(10, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
        assertEquals("Media3 read the volume it set", 10, player.deviceVolume)
        assertEquals(10 / steps.toFloat(), b.state.value.volumeFraction, 0.001f)
    }

    @Test
    fun `with the app's own volume, a move to another output leaves the slider alone`() {
        val b = backend()
        b.setVolume(1f)
        idleEverything()
        b.setVolumeMode(VolumeMode.APP)
        b.setVolume(0.5f) // the app's gain
        idleEverything()

        moveOutput(4)

        assertEquals(0.5f, b.state.value.volumeFraction, 0.001f)
    }

    /**
     * The fold into the phone's volume starts from the output now playing, so
     * switching back after a move from the headphones to the speaker keeps
     * the speaker's loudness.
     */
    @Test
    fun `switching back to the phone's volume after a move to another output keeps the loudness`() {
        val b = backend()
        b.setVolume(1f) // the headphones' volume, at the top
        idleEverything()
        b.setVolumeMode(VolumeMode.APP)
        b.setVolume(0.5f) // the app's gain, half
        idleEverything()
        moveOutput(4) // the speaker's volume

        b.setVolumeMode(VolumeMode.DEVICE)
        idleEverything()

        // half of the speaker's 4 steps
        assertEquals(2, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
    }

    @Test
    fun `releasing the backend stops the reading`() {
        val b = backend()
        b.setVolume(1f)
        idleEverything()
        val before = b.state.value.volumeFraction

        b.release()
        backend = null
        moveOutput(4)

        assertEquals(before, b.state.value.volumeFraction, 0.001f)
    }
}
