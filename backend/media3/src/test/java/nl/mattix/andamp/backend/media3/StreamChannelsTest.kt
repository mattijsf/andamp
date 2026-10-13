// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.backend.media3

import android.content.Context
import android.os.Looper
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.test.utils.robolectric.ShadowMediaCodecConfig
import androidx.media3.test.utils.robolectric.TestPlayerRunHelper
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import nl.mattix.andamp.core.model.Track
import nl.mattix.andamp.core.playback.PcmRingBuffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/**
 * The channel count behind the mono and stereo lamps, against real mp3 files.
 *
 * The count is the audio output's, so the files are decoded: the rule gives
 * the player a decoder, which a Robolectric run does not have otherwise.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class StreamChannelsTest {
    @get:Rule
    val temp = TemporaryFolder()

    @get:Rule
    val codecs: ShadowMediaCodecConfig = ShadowMediaCodecConfig.withAllDefaultSupportedCodecs()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var backend: Media3Backend? = null
    private lateinit var player: ExoPlayer

    @After
    fun tearDown() {
        backend?.release()
        scope.cancel()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun track(name: String): Track {
        val bytes = javaClass.classLoader!!.getResourceAsStream(name)!!.use { it.readBytes() }
        val file: File = temp.newFile(name).apply { writeBytes(bytes) }
        return Track("lib:$name", "", name, 0, uri = file.toURI().toString())
    }

    /** A backend over [names], not yet playing. */
    private fun backendOf(vararg names: String): Media3Backend {
        val context = ApplicationProvider.getApplicationContext<Context>()
        player = ExoPlayer.Builder(context, chained(context)).build()
        val b = Media3Backend(names.map(::track), scope, player).also { backend = it }
        shadowOf(Looper.getMainLooper()).idle()
        return b
    }

    /**
     * A renderers factory whose audio sink runs [audioChain], as the app's
     * player does: the count is read after the chain's stages.
     */
    private fun chained(context: Context): DefaultRenderersFactory {
        val chain = audioChain(EqAudioProcessor(), BalanceAudioProcessor(), DspAudioProcessor(), PcmRingBuffer())
        return object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ): AudioSink = DefaultAudioSink.Builder(context).setAudioProcessors(chain.toTypedArray()).build()
        }
    }

    /** Plays until the row that is on can be heard. */
    private fun Media3Backend.heard(): Media3Backend {
        TestPlayerRunHelper.play(player).untilState(Player.STATE_READY)
        shadowOf(Looper.getMainLooper()).idle()
        return this
    }

    @Test
    fun `nothing is known before a track is heard`() {
        assertNull(backendOf("tone-mono.mp3").state.value.streamChannels)
    }

    @Test
    fun `a mono file is one channel`() {
        val b = backendOf("tone-mono.mp3").apply { play() }.heard()

        assertEquals(1, b.state.value.streamChannels)
    }

    @Test
    fun `a stereo file is two channels`() {
        val b = backendOf("tone-cbr-192.mp3").apply { play() }.heard()

        assertEquals(2, b.state.value.streamChannels)
    }

    @Test
    fun `the count follows the track that is on`() {
        val b = backendOf("tone-mono.mp3", "tone-cbr-192.mp3").apply { play() }.heard()
        assertEquals(1, b.state.value.streamChannels)

        b.next()
        b.heard()

        assertEquals(2, b.state.value.streamChannels)
    }

    @Test
    fun `a pause and a seek keep the count`() {
        val b = backendOf("tone-mono.mp3").apply { play() }.heard()

        b.pause()
        b.seekTo(200)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(1, b.state.value.streamChannels)
    }
}
