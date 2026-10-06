// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.backend.media3

import android.content.Context
import android.media.AudioManager
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.test.utils.FakeMediaSource
import androidx.media3.test.utils.FakeMediaSourceFactory
import androidx.media3.test.utils.FakeTimeline
import androidx.media3.test.utils.robolectric.TestPlayerRunHelper
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import nl.mattix.andamp.core.model.Track
import nl.mattix.andamp.core.model.Transport
import nl.mattix.andamp.core.model.VolumeMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/**
 * Mirrors PlaybackBackendContractTest's control-surface cases against a real
 * ExoPlayer under Robolectric. The contract test runs on a virtual coroutine
 * clock, which ExoPlayer does not follow, so the clock-driven cases (position
 * advance, track-end auto-advance) are left out here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class Media3BackendTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var backend: Media3Backend? = null
    private var player: ExoPlayer? = null

    private fun tracks(vararg durationsSec: Int): List<Track> =
        durationsSec.mapIndexed { i, sec -> Track("t$i", "Artist $i", "Title $i", sec * 1000L) }

    private fun backend(vararg durationsSec: Int): Media3Backend =
        backend(sources = FakeMediaSourceFactory(), durationsSec = durationsSec)

    private fun backend(
        sources: MediaSource.Factory,
        vararg durationsSec: Int,
    ): Media3Backend {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // fake sources prepare cleanly (10 s windows); empty real URIs would
        // raise async player errors that change the transport mid-test
        val exo =
            ExoPlayer
                .Builder(context)
                .setMediaSourceFactory(sources)
                .setDeviceVolumeControlEnabled(true) // as the real one is built: the slider is the device's volume
                .build()
        player = exo
        return Media3Backend(tracks(*durationsSec), scope, exo).also {
            backend = it
            idle()
        }
    }

    /**
     * A station started from a list of files holds the Wi-Fi radio awake.
     *
     * The wake mode is chosen in `onMediaItemTransition`, which ExoPlayer fires
     * from inside `setMediaItems`, so it has to come from the player's own
     * item.
     */
    @Test
    fun `a station replacing a list of files keeps the wifi awake`() {
        val subject = backend(10, 10)
        val modes = mutableListOf<Int>()
        subject.wakeMode = { modes += it }

        subject.setQueue(listOf(Track("radio", "", "Station", 0, uri = "https://example.test/live", isStream = true)), 0)
        idle()

        assertEquals("the wake mode is the station's", C.WAKE_MODE_NETWORK, modes.last())
    }

    /**
     * Going back to files returns to the local wake mode, with the files in
     * the player.
     */
    @Test
    fun `files replacing a station let the wifi sleep, and the station stays gone`() {
        val subject = backend(10)
        subject.setQueue(listOf(Track("radio", "", "Station", 0, uri = "https://example.test/live", isStream = true)), 0)
        idleEverything()
        val modes = mutableListOf<Int>()
        subject.wakeMode = { modes += it }

        subject.setQueue(tracks(10, 10), 0)
        idleEverything()

        val items = (0 until (player?.mediaItemCount ?: 0)).map { player?.getMediaItemAt(it)?.mediaId }
        assertEquals("the player holds the files it is given", listOf("t0", "t1"), items)
        assertEquals(C.WAKE_MODE_LOCAL, modes.last())
    }

    /** A source whose one track is audio of a known shape, like a decoded file. */
    private class AudioSources(
        private val format: Format,
    ) : MediaSource.Factory {
        override fun setDrmSessionManagerProvider(provider: DrmSessionManagerProvider) = this

        override fun setLoadErrorHandlingPolicy(policy: LoadErrorHandlingPolicy) = this

        override fun getSupportedTypes() = intArrayOf(C.CONTENT_TYPE_OTHER)

        override fun createMediaSource(mediaItem: MediaItem): MediaSource = FakeMediaSource(FakeTimeline(1), format)
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /**
     * Media3 keeps the device's volume on a background thread, so the main
     * looper alone never sees it.
     */
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
            idle()
        }
    }

    @After
    fun tearDown() {
        backend?.release()
        scope.cancel()
        idle()
    }

    @Test
    fun `starts stopped at the queue head with position zero`() {
        val b = backend(10, 20)
        val s = b.state.value
        assertEquals(Transport.Stopped, s.transport)
        assertEquals(0L, s.positionMs)
        assertEquals(0, s.currentIndex)
        assertEquals(2, s.queue.size)
    }

    @Test
    fun `play starts playback`() {
        val b = backend(10)
        b.play()
        idle()
        assertEquals(Transport.Playing, b.state.value.transport)
    }

    @Test
    fun `pause toggles and does nothing while stopped`() {
        val b = backend(10)
        b.pause()
        idle()
        assertEquals(Transport.Stopped, b.state.value.transport)
        b.play()
        idle()
        b.pause()
        idle()
        assertEquals(Transport.Paused, b.state.value.transport)
        b.pause()
        idle()
        assertEquals(Transport.Playing, b.state.value.transport)
    }

    @Test
    fun `stopping through the session is the same stop as the app's own`() {
        // the widget's transport, the notification and a headset all arrive as
        // media buttons, which the session turns into calls on the Player it
        // holds. ExoPlayer.stop called directly would halt playback without
        // moving playWhenReady, and the state would go on saying Playing
        val b = backend(10)
        b.play()
        idle()
        b.seekTo(3000)
        idle()

        b.playerForSession.stop()
        idle()

        assertEquals(Transport.Stopped, b.state.value.transport)
        assertEquals(0L, b.state.value.positionMs)
    }

    @Test
    fun `a play pressed during a fade-out plays on, at full gain, when the fade would have ended`() {
        val b = backend(10, 20)
        b.play()
        idle()

        b.stopWithFadeout()
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(300))
        b.play()
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(2))

        assertEquals("the fade's stop does not stop the music started after it", Transport.Playing, b.state.value.transport)
        assertEquals("the gain the fade walked down is restored", 1f, player!!.volume, 0.001f)
    }

    @Test
    fun `stop resets the position`() {
        val b = backend(10)
        b.play()
        idle()
        b.seekTo(3000)
        idle()
        b.stop()
        idle()
        assertEquals(Transport.Stopped, b.state.value.transport)
        assertEquals(0L, b.state.value.positionMs)
    }

    @Test
    fun `next and previous wrap around the queue`() {
        val b = backend(10, 20, 30)
        b.next()
        idle()
        assertEquals(1, b.state.value.currentIndex)
        b.next()
        idle()
        b.next()
        idle()
        assertEquals(0, b.state.value.currentIndex)
        b.previous()
        idle()
        assertEquals(2, b.state.value.currentIndex)
    }

    @Test
    fun `playAt jumps to the track and starts playing from zero`() {
        val b = backend(10, 20, 30)
        b.playAt(2)
        idle()
        val s = b.state.value
        assertEquals(2, s.currentIndex)
        assertEquals(Transport.Playing, s.transport)
        assertEquals(0L, s.positionMs)
    }

    @Test
    fun `seek clamps to the track duration`() {
        val b = backend(10)
        b.play()
        idle()
        b.seekTo(99_000)
        idle()
        assertEquals(10_000L, b.state.value.positionMs)
        b.seekTo(-5)
        idle()
        assertEquals(0L, b.state.value.positionMs)
    }

    @Test
    fun `a player left idle plays again when asked`() {
        val b = backend(10)
        idle()
        // an unreadable source leaves ExoPlayer idle, and it does not play
        // until it is prepared again
        player!!.stop()
        idle()

        b.play()
        idle()

        assertEquals(Transport.Playing, b.state.value.transport)
        // only leaving idle is asserted: ready or buffering depends on the source
        assertNotEquals(Player.STATE_IDLE, player!!.playbackState)
    }

    @Test
    fun `the slider starts where the phone's volume already is`() {
        val audio = ApplicationProvider.getApplicationContext<Context>().getSystemService(AudioManager::class.java)
        val steps = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, steps / 3, 0)

        val b = backend(10)
        idleEverything()

        // not the default of full: the phone already had a volume
        assertEquals((steps / 3) / steps.toFloat(), b.state.value.volumeFraction, 0.001f)
    }

    @Test
    fun `switching to its own volume leaves the phone's alone and does not change loudness`() {
        val b = backend(10)
        idleEverything()
        val audio = ApplicationProvider.getApplicationContext<Context>().getSystemService(AudioManager::class.java)
        val steps = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        b.setVolume(0.5f)
        idleEverything()
        val deviceBefore = audio.getStreamVolume(AudioManager.STREAM_MUSIC)

        b.setVolumeMode(VolumeMode.APP)
        idleEverything()

        // what was heard was the device alone; the app's gain stays at 1
        assertEquals(
            "switching to the app's volume leaves the phone's volume unchanged",
            deviceBefore,
            audio.getStreamVolume(AudioManager.STREAM_MUSIC),
        )
        assertEquals("the app's gain stays at full", 1f, b.playerForSession.volume, 0.001f)
        assertTrue("the device has a volume range", steps > 0)
    }

    @Test
    fun `switching back folds our attenuation into the phone's volume`() {
        val b = backend(10)
        idleEverything()
        val audio = ApplicationProvider.getApplicationContext<Context>().getSystemService(AudioManager::class.java)
        val steps = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        b.setVolume(1f) // device wide open
        idleEverything()
        b.setVolumeMode(VolumeMode.APP)
        idleEverything()
        b.setVolume(0.5f) // the app's gain alone, half
        idleEverything()

        b.setVolumeMode(VolumeMode.DEVICE)
        idleEverything()

        // half of a wide-open device is half the stream. The step is whatever
        // DeviceVolume rounds half to
        assertEquals(DeviceVolume.stepFor(0.5f, 0, steps), audio.getStreamVolume(AudioManager.STREAM_MUSIC))
        assertEquals(1f, b.playerForSession.volume, 0.001f)
    }

    @Test
    fun `tags arriving late reach the notification, not just the playlist`() {
        // ADD > DIR queues rows with no tags, so their media items are built
        // with an empty title. The tags arrive later, and have to reach the
        // media items as well as the queue.
        val b = backend(10)
        idleEverything()
        val nameless =
            b.state.value.queue[0]
                .copy(artist = "", title = "", defaultName = "04 - Cryogen.mp3")
        b.setQueue(listOf(nameless))
        idleEverything()

        b.patchTracks(listOf(nameless.copy(artist = "Muse", title = "Cryogen")))
        idleEverything()

        assertEquals(
            "Cryogen",
            b.state.value.queue[0]
                .title,
        )
        assertEquals(
            "the media item the notification reads carries the late tags",
            "Cryogen",
            b.playerForSession
                .getMediaItemAt(0)
                .mediaMetadata.title,
        )
    }

    @Test
    fun `shuffle thrown from outside shows on the player`() {
        // the notification's own buttons set the player directly, as a
        // headset does, and the state mirrors it
        val b = backend(10, 20)
        idleEverything()
        assertEquals(false, b.state.value.shuffle)

        b.playerForSession.shuffleModeEnabled = true
        idleEverything()

        assertEquals(true, b.state.value.shuffle)
    }

    @Test
    fun `repeat thrown from outside shows on the player`() {
        val b = backend(10, 20)
        idleEverything()

        b.playerForSession.repeatMode = androidx.media3.common.Player.REPEAT_MODE_ALL
        idleEverything()

        assertEquals(true, b.state.value.repeat)
    }

    @Test
    fun `the slider moves the phone's media volume`() {
        val b = backend(10)
        idleEverything()
        val audio = ApplicationProvider.getApplicationContext<Context>().getSystemService(AudioManager::class.java)
        val steps = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

        b.setVolume(1f)
        idleEverything()
        assertEquals(steps, audio.getStreamVolume(AudioManager.STREAM_MUSIC))

        b.setVolume(0f)
        idleEverything()
        assertEquals(0, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
    }

    @Test
    fun `the slider shows where the device landed, not what was asked for`() {
        val b = backend(10)
        idleEverything()
        val audio = ApplicationProvider.getApplicationContext<Context>().getSystemService(AudioManager::class.java)
        val steps = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

        // a fraction between two steps: the device rounds, and the slider
        // shows where it landed
        val between = (1f / steps) * 0.5f + (1f / steps)
        b.setVolume(between)
        idleEverything()

        val landed = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        assertEquals(landed / steps.toFloat(), b.state.value.volumeFraction, 0.001f)
    }

    @Test
    fun `a volume change from outside moves the slider`() {
        val b = backend(10)
        idleEverything()
        val audio = ApplicationProvider.getApplicationContext<Context>().getSystemService(AudioManager::class.java)
        val steps = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

        // what the volume keys and the system panel do
        player?.setDeviceVolume(steps / 3, 0)
        idleEverything()

        assertEquals((steps / 3) / steps.toFloat(), b.state.value.volumeFraction, 0.001f)
    }

    @Test
    fun `volume clamps to the unit range`() {
        val b = backend(10)
        idleEverything()
        b.setVolume(1.5f)
        idleEverything()
        assertEquals(1f, b.state.value.volumeFraction)
        b.setVolume(-0.5f)
        idleEverything()
        assertEquals(0f, b.state.value.volumeFraction)
    }

    /**
     * Eight rows under shuffle, in an order the test sets: 0, 5, 2, 7, 1, 6, 3, 4. Playback
     * moves through the rows in that order by itself, and the skip buttons have to as well:
     * a Next that goes to the row below is a shuffle that only works while nobody touches it.
     */
    private fun shuffled(): Media3Backend {
        val b = backend(10, 10, 10, 10, 10, 10, 10, 10)
        b.setShuffle(true)
        player!!.setShuffleOrder(
            androidx.media3.exoplayer.source.ShuffleOrder
                .DefaultShuffleOrder(intArrayOf(0, 5, 2, 7, 1, 6, 3, 4), 0L),
        )
        idle()
        return b
    }

    @Test
    fun `next under shuffle goes to the row the shuffle has next`() {
        val b = shuffled()
        b.play()
        idle()

        b.next()
        idle()
        assertEquals(5, b.state.value.currentIndex)

        b.next()
        idle()
        assertEquals(2, b.state.value.currentIndex)
        assertEquals(Transport.Playing, b.state.value.transport)
    }

    @Test
    fun `previous under shuffle goes back the way it came`() {
        val b = shuffled()
        b.play()
        idle()
        b.next()
        b.next()
        idle()

        b.previous()
        idle()
        assertEquals(5, b.state.value.currentIndex)

        b.previous()
        idle()
        assertEquals(0, b.state.value.currentIndex)
    }

    @Test
    fun `next past the last row of the shuffle starts the shuffle again, as next wraps without it`() {
        val b = shuffled()
        b.playAt(4)
        idle()

        b.next()
        idle()

        assertEquals(0, b.state.value.currentIndex)
        assertEquals(Transport.Playing, b.state.value.transport)
    }

    @Test
    fun `previous before the first row of the shuffle goes to its last`() {
        val b = shuffled()
        b.play()
        idle()

        b.previous()
        idle()

        assertEquals(4, b.state.value.currentIndex)
    }

    @Test
    fun `a skip while stopped moves the cursor along the shuffle and plays nothing`() {
        val b = shuffled()

        b.next()
        idle()

        assertEquals(5, b.state.value.currentIndex)
        assertEquals(Transport.Stopped, b.state.value.transport)
    }

    @Test
    fun `shuffle and repeat map onto the player modes`() {
        val b = backend(10, 20)
        b.setShuffle(true)
        b.setRepeat(true)
        idle()
        assertEquals(true, player!!.shuffleModeEnabled)
        assertEquals(androidx.media3.common.Player.REPEAT_MODE_ALL, player!!.repeatMode)
        assertEquals(true, b.state.value.shuffle)
        assertEquals(true, b.state.value.repeat)
    }

    @Test
    fun `external pause and play are mirrored into the transport`() {
        // the media notification and headset buttons drive the player directly,
        // bypassing the backend API; the transport must follow
        val b = backend(10)
        b.play()
        idle()
        player!!.pause()
        idle()
        assertEquals(Transport.Paused, b.state.value.transport)
        player!!.play()
        idle()
        assertEquals(Transport.Playing, b.state.value.transport)
    }

    @Test
    fun `setQueue keeps playback running when the current track survives the edit`() {
        val b = backend(10, 20, 30)
        b.play()
        idle()
        b.seekTo(2000)
        idle()
        val queue = b.state.value.queue
        b.setQueue(listOf(queue[0], queue[2]))
        idle()
        val s = b.state.value
        assertEquals(Transport.Playing, s.transport)
        assertEquals(0, s.currentIndex)
        assertEquals(2, s.queue.size)
        assertTrue("the position is preserved: ${s.positionMs}", s.positionMs >= 0)
        assertEquals(2000L, player!!.currentPosition)
    }

    @Test
    fun `setQueue stops when the current track is removed`() {
        val b = backend(10, 20)
        b.play()
        idle()
        b.setQueue(listOf(b.state.value.queue[1]))
        idle()
        val s = b.state.value
        assertEquals(Transport.Stopped, s.transport)
        assertEquals(0L, s.positionMs)
    }

    @Test
    fun `enqueue appends without interrupting playback`() {
        val b = backend(10, 20)
        b.play()
        idle()
        b.seekTo(2000)
        idle()
        b.enqueue(listOf(Track("e0", "Artist e", "Extra", 5_000)))
        idle()
        val s = b.state.value
        assertEquals(Transport.Playing, s.transport)
        assertEquals(0, s.currentIndex)
        assertEquals(listOf("t0", "t1", "e0"), s.queue.map { it.id })
        assertEquals(3, player!!.mediaItemCount)
        assertEquals(2000L, player!!.currentPosition)
    }

    @Test
    fun `enqueue while stopped leaves the cursor where it was`() {
        val b = backend(10, 20, 30)
        b.next() // cursor parked mid-queue, still stopped
        idle()
        b.enqueue(listOf(Track("e0", "Artist e", "Extra", 5_000)))
        idle()
        val s = b.state.value
        assertEquals(Transport.Stopped, s.transport)
        assertEquals(1, s.currentIndex)
        assertEquals(0L, s.positionMs)
        assertEquals(4, s.queue.size)
    }

    @Test
    fun `setQueue with an empty list stops at index zero`() {
        val b = backend(10)
        b.play()
        idle()
        b.setQueue(emptyList())
        idle()
        val s = b.state.value
        assertEquals(Transport.Stopped, s.transport)
        assertEquals(0, s.currentIndex)
        assertTrue(s.queue.isEmpty())
    }

    @Test
    fun `the kbps and kHz readouts come from the stream, not from tags`() {
        // a library row may carry no bitrate or sample rate; the decoder
        // reports both
        val mp3 =
            Format
                .Builder()
                .setSampleMimeType(MimeTypes.AUDIO_MPEG)
                .setAverageBitrate(192_000)
                .setSampleRate(44_100)
                .setChannelCount(2)
                .build()
        val b = backend(sources = AudioSources(mp3), durationsSec = intArrayOf(10))
        b.play()
        TestPlayerRunHelper.run(player!!).untilState(Player.STATE_READY)
        idle()
        val track = b.state.value.currentTrack!!
        assertEquals(192, track.bitrateKbps)
        assertEquals(44, track.sampleRateKhz)
    }

    /**
     * Exit stops the session service, so the media notification goes with it.
     * `WinampViewModelExitTest` holds the backend-neutral half.
     */
    @Test
    fun `teardown stops the session service`() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val subject = backend(10)
        Media3PlaybackHost.ensureService(app)

        Media3Backend(tracks(10), scope, subject.playerForSession, host = app).teardown()

        val stopped =
            org.robolectric.Shadows
                .shadowOf(app)
                .nextStoppedService
        assertTrue(
            "teardown stops the playback service",
            stopped != null && stopped.component?.className == PlaybackService::class.java.name,
        )
    }

    /** A backend built without a host has no service; neither call may throw. */
    @Test
    fun `keepAlive and teardown are safe with no host`() {
        val subject = backend(10)

        subject.keepAlive()
        subject.teardown()
    }
}
