// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.backend.media3

import android.content.Context
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.drm.DrmSessionEventListener
import androidx.media3.exoplayer.drm.DrmSessionManager
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.source.MediaPeriod
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MediaSourceEventListener
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.upstream.Allocator
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.test.utils.FakeMediaPeriod
import androidx.media3.test.utils.FakeMediaSource
import androidx.media3.test.utils.FakeTimeline
import androidx.media3.test.utils.TestExoPlayerBuilder
import androidx.media3.test.utils.robolectric.RobolectricUtil
import androidx.media3.test.utils.robolectric.TestPlayerRunHelper
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import nl.mattix.andamp.core.model.BackendNotice
import nl.mattix.andamp.core.model.Track
import nl.mattix.andamp.core.model.Transport
import nl.mattix.andamp.core.playback.NetworkWatch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.IOException
import java.time.Duration

/**
 * A station that drops, on a real ExoPlayer: reconnected while the listener
 * wants it, left alone when they do not, not retried for an error that will
 * not pass, and given up on with a notice when it does not come back.
 *
 * The sources fail at prepare with the error a broken connection raises, and
 * the waits advance the main looper's clock, so minutes of backoff pass in
 * milliseconds.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class StationRedialTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var backend: Media3Backend? = null
    private lateinit var player: ExoPlayer

    private val station = Track("radio", "", "Station", 0, uri = "https://example.test/live", isStream = true)
    private val file = Track("file", "Artist", "Song", 10_000, uri = "file:///song.mp3")

    /** Sources that fail to prepare with [failWith] while it is set, and play when it is not. */
    private class Droppable : MediaSource.Factory {
        // set by the test, read on the player's own thread
        @Volatile
        var failWith: IOException? = null

        /** How long each row lasts before its source says it has ended. */
        @Volatile
        var lengthUs = 10_000_000L

        /** Every time a row was reached for: a first connection, and each reconnect. */
        @Volatile
        var reaches = 0

        private val audio =
            Format
                .Builder()
                .setSampleMimeType(MimeTypes.AUDIO_MPEG)
                .setAverageBitrate(128_000)
                .setSampleRate(44_100)
                .setChannelCount(2)
                .build()

        override fun setDrmSessionManagerProvider(provider: DrmSessionManagerProvider) = this

        override fun setLoadErrorHandlingPolicy(policy: LoadErrorHandlingPolicy) = this

        override fun getSupportedTypes() = intArrayOf(C.CONTENT_TYPE_OTHER)

        override fun createMediaSource(mediaItem: MediaItem): MediaSource =
            // the window carries the item it was made for, as a real source's
            // does; the backend tells a station from a file by it
            object : FakeMediaSource(
                FakeTimeline(
                    FakeTimeline.TimelineWindowDefinition
                        .Builder()
                        .setMediaItem(mediaItem)
                        .setDurationUs(lengthUs)
                        .build(),
                ),
                audio,
            ) {
                override fun createMediaPeriod(
                    id: MediaSource.MediaPeriodId,
                    trackGroupArray: TrackGroupArray,
                    allocator: Allocator,
                    mediaSourceEventDispatcher: MediaSourceEventListener.EventDispatcher,
                    drmSessionManager: DrmSessionManager,
                    drmEventDispatcher: DrmSessionEventListener.EventDispatcher,
                    transferListener: TransferListener?,
                ): MediaPeriod {
                    reaches++
                    val failure =
                        failWith ?: return super.createMediaPeriod(
                            id,
                            trackGroupArray,
                            allocator,
                            mediaSourceEventDispatcher,
                            drmSessionManager,
                            drmEventDispatcher,
                            transferListener,
                        )
                    return object : FakeMediaPeriod(
                        trackGroupArray,
                        allocator,
                        0L,
                        mediaSourceEventDispatcher,
                        drmSessionManager,
                        drmEventDispatcher,
                        true, // never prepares: it only throws the error below
                    ) {
                        override fun maybeThrowPrepareError() = throw failure
                    }
                }
            }
    }

    /** A network the test switches off and on, and can see being watched. */
    private class Network(
        override var online: Boolean = true,
    ) : NetworkWatch {
        var hear: ((Boolean) -> Unit)? = null

        override fun watch(onChange: (online: Boolean) -> Unit) {
            hear = onChange
        }

        override fun unwatch() {
            hear = null
        }

        fun comesBack() {
            online = true
            hear?.invoke(true)
        }
    }

    private val sources = Droppable()
    private val network = Network()

    private fun backendOf(vararg tracks: Track): Media3Backend {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // fake renderers on a clock that advances when the player waits: under
        // Robolectric the real audio path can stay in buffering on a source
        // prepared a second time
        player = TestExoPlayerBuilder(context).setMediaSourceFactory(sources).build()
        return Media3Backend(tracks.toList(), scope, player, network = network).also {
            backend = it
            idle()
        }
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun waitFor(duration: Duration) = shadowOf(Looper.getMainLooper()).idleFor(duration)

    private fun untilError() {
        TestPlayerRunHelper.run(player).untilPlayerError()
        idle()
    }

    private fun networkError() = DataSourceException(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)

    @After
    fun tearDown() {
        backend?.release()
        scope.cancel()
        idle()
    }

    @Test
    fun `a station that drops is prepared again on the same row, and plays on`() {
        val subject = backendOf(station, file)
        sources.failWith = networkError()
        subject.play()
        untilError()
        val reachedBefore = sources.reaches

        val during = subject.state.value
        assertEquals("the transport stays playing through the drop", Transport.Playing, during.transport)
        assertTrue("the state reports connecting", during.connecting)

        sources.failWith = null
        waitFor(Duration.ofSeconds(2))
        TestPlayerRunHelper.run(player).untilState(Player.STATE_READY)
        idle()

        val after = subject.state.value
        assertTrue("the station is reached for again", sources.reaches > reachedBefore)
        assertEquals("the cursor stays on the station", 0, after.currentIndex)
        assertEquals(0, player.currentMediaItemIndex)
        assertEquals(Transport.Playing, after.transport)
        assertFalse("connecting clears once the station plays", after.connecting)
        assertNull("the network is not watched once the station plays", network.hear)
    }

    /**
     * An Icecast server hanging up raises no error: the stream ends.
     */
    @Test
    fun `a station whose server hangs up is connected to again rather than stopped`() {
        sources.lengthUs = 200_000
        val subject = backendOf(station)
        subject.play()
        TestPlayerRunHelper.run(player).untilState(Player.STATE_ENDED)
        idle()
        val reachedBefore = sources.reaches

        assertEquals(
            "the transport stays playing when the station's stream ends",
            Transport.Playing,
            subject.state.value.transport,
        )
        assertTrue(subject.state.value.connecting)

        waitFor(Duration.ofSeconds(2))
        // the same short source ends again; what is asserted is that it was
        // reached for, on the same row
        RobolectricUtil.runMainLooperUntil { sources.reaches > reachedBefore }

        assertEquals(0, player.currentMediaItemIndex)
        assertEquals(Transport.Playing, subject.state.value.transport)
    }

    @Test
    fun `a pause while waiting to reconnect calls the reconnect off`() {
        val subject = backendOf(station)
        sources.failWith = networkError()
        subject.play()
        untilError()
        val reachedBefore = sources.reaches

        subject.pause()
        sources.failWith = null
        waitFor(Duration.ofMinutes(2))

        assertEquals("no retry is made after the pause", reachedBefore, sources.reaches)
        assertEquals(Transport.Paused, subject.state.value.transport)
        assertEquals(Player.STATE_IDLE, player.playbackState)
        assertNull("the network is not watched while paused", network.hear)

        subject.pause()
        TestPlayerRunHelper.run(player).untilState(Player.STATE_READY)
        assertEquals("resuming reconnects", Transport.Playing, subject.state.value.transport)
    }

    @Test
    fun `a station that is not there is not asked again`() {
        val subject = backendOf(station)
        sources.failWith =
            HttpDataSource.InvalidResponseCodeException(
                404,
                "Not Found",
                null,
                emptyMap(),
                DataSpec.Builder().setUri("x").build(),
                ByteArray(0),
            )
        subject.play()
        untilError()
        val reachedBefore = sources.reaches

        waitFor(Duration.ofMinutes(2))

        assertEquals(reachedBefore, sources.reaches)
        assertEquals(Transport.Stopped, subject.state.value.transport)
        assertNull("a 404 raises no StationLost notice", subject.state.value.notice)
    }

    @Test
    fun `a server in trouble is asked again`() {
        assertTrue(StationRedial.heals(PlaybackException("", status(503), PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS)))
        assertFalse(StationRedial.heals(PlaybackException("", status(403), PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS)))
        assertFalse(StationRedial.heals(PlaybackException("", null, PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED)))
        assertTrue(StationRedial.heals(PlaybackException("", null, PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW)))
    }

    private fun status(code: Int) =
        HttpDataSource.InvalidResponseCodeException(
            code,
            "",
            null,
            emptyMap(),
            DataSpec.Builder().setUri("x").build(),
            ByteArray(0),
        )

    @Test
    fun `a file that fails stops`() {
        val subject = backendOf(file, file.copy(id = "second"))
        sources.failWith = networkError()
        subject.play()
        untilError()
        val reachedBefore = sources.reaches

        waitFor(Duration.ofMinutes(2))

        assertEquals(reachedBefore, sources.reaches)
        assertEquals(Transport.Stopped, subject.state.value.transport)
        assertFalse(subject.state.value.connecting)
    }

    @Test
    fun `with no network nothing is tried until one comes back, and then once`() {
        val subject = backendOf(station)
        network.online = false
        sources.failWith = networkError()
        subject.play()
        untilError()
        val reachedBefore = sources.reaches

        waitFor(Duration.ofMinutes(20))
        assertEquals("no retry is made while offline", reachedBefore, sources.reaches)
        assertEquals("the transport stays playing while offline", Transport.Playing, subject.state.value.transport)
        assertNull(subject.state.value.notice)

        sources.failWith = null
        network.comesBack()
        waitFor(Duration.ofSeconds(1))
        TestPlayerRunHelper.run(player).untilState(Player.STATE_READY)
        idle()

        assertEquals("one retry is made when the network comes back", reachedBefore + 1, sources.reaches)
        assertEquals(Transport.Playing, subject.state.value.transport)
    }

    @Test
    fun `a station that never comes back stops and says so`() {
        val subject = backendOf(station)
        sources.failWith = networkError()
        subject.play()
        untilError()

        repeat(TRIES_TO_OUTLAST) {
            waitFor(Duration.ofSeconds(40))
            if (player.playbackState != Player.STATE_IDLE) {
                TestPlayerRunHelper.run(player).untilPlayerError()
            }
            idle()
        }

        val s = subject.state.value
        assertEquals(Transport.Stopped, s.transport)
        assertEquals(BackendNotice.StationLost, s.notice)
        assertFalse(s.connecting)
        assertFalse("playWhenReady is cleared after giving up", player.playWhenReady)
        assertNull("the network is not watched after giving up", network.hear)

        // and play is the way back
        sources.failWith = null
        subject.play()
        TestPlayerRunHelper.run(player).untilState(Player.STATE_READY)
        assertNotEquals(Transport.Stopped, subject.state.value.transport)
    }

    private companion object {
        /** More waits of forty seconds than the budget allows tries. */
        const val TRIES_TO_OUTLAST = 14
    }
}
