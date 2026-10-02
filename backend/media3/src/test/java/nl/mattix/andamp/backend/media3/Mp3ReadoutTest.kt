// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.backend.media3

import android.content.Context
import android.os.Looper
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.test.utils.robolectric.TestPlayerRunHelper
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import nl.mattix.andamp.core.model.Track
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/** The layer III bitrates the tests accept, in kbit/s. */
private val MP3_RUNGS = setOf(8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320)

/**
 * The kbps/kHz readouts against real mp3 files, read by Media3's extractor.
 *
 * A library row may arrive with no bitrate or sample rate. A CBR file carries
 * its bitrate in every frame header, and a VBR file's average comes from the
 * header the encoder wrote. Both have to reach the display.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class Mp3ReadoutTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var backend: Media3Backend? = null

    @After
    fun tearDown() {
        backend?.release()
        scope.cancel()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun fixture(name: String): File {
        val bytes = javaClass.classLoader!!.getResourceAsStream(name)!!.use { it.readBytes() }
        return temp.newFile(name).apply { writeBytes(bytes) }
    }

    /** The queue entry after the player has read [name] far enough to say. */
    private fun readoutsOf(name: String): Track = playing(name).state.value.currentTrack!!

    /** A backend playing [name], with its frames walked. */
    private fun playing(name: String): Media3Backend {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val player = ExoPlayer.Builder(context).build()
        val file = fixture(name)
        val track = Track("lib:1", "", name, 0, uri = file.toURI().toString())
        val b =
            Media3Backend(
                listOf(track),
                scope,
                player,
                // the frame walk runs on the main looper, which the test idles
                openTrack = { file.inputStream() },
                scanContext = Dispatchers.Main,
            ).also { backend = it }
        shadowOf(Looper.getMainLooper()).idle()
        b.play()
        TestPlayerRunHelper.run(player).ignoringNonFatalErrors().untilState(Player.STATE_READY)
        shadowOf(Looper.getMainLooper()).idle()
        return b
    }

    @Test
    fun `a constant bitrate mp3 fills both readouts`() {
        val track = readoutsOf("tone-cbr-192.mp3")

        assertEquals(192, track.bitrateKbps)
        assertEquals(44, track.sampleRateKhz)
    }

    @Test
    fun `a variable bitrate mp3 reads as its average`() {
        val track = readoutsOf("tone-vbr.mp3")

        // a VBR file sits between the ladder's rungs, so it keeps the average
        // Media3 reported
        assertEquals(59, track.bitrateKbps)
        assertEquals(44, track.sampleRateKhz)
    }

    @Test
    fun `the readout moves with the needle through a variable bitrate file`() {
        val table = javaClass.classLoader!!.getResourceAsStream("tone-vbr.mp3")!!.use { Mp3Frames.scan(it) }!!
        val frameMs = table.frameDurationUs / 1000L
        val first = table.kbpsAt(0)
        // the first frame that carries a different rate
        val elsewhere =
            (1 until table.frameCount)
                .map { it * frameMs }
                .first { table.kbpsAt(it) != first }
        val b = playing("tone-vbr.mp3")

        assertEquals(first, b.state.value.streamBitrateKbps)
        b.seekTo(elsewhere)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(table.kbpsAt(elsewhere), b.state.value.streamBitrateKbps)
        assertNotEquals("the readout moves", first, b.state.value.streamBitrateKbps)
    }

    @Test
    fun `the readout follows the frames of a variable bitrate file`() {
        // Media3 knows only the file's average, so the frames are walked and
        // the readout looks up the frame at the position being played
        val b = playing("tone-vbr.mp3")

        val atStart = b.state.value.streamBitrateKbps

        assertNotNull("a frame rate is reported", atStart)
        assertTrue("the frame rate is a layer III bitrate: $atStart", atStart in MP3_RUNGS)
    }

    @Test
    fun `a constant bitrate file reports the rate its frames carry`() {
        val b = playing("tone-cbr-192.mp3")

        assertEquals(192, b.state.value.streamBitrateKbps)
    }
}
