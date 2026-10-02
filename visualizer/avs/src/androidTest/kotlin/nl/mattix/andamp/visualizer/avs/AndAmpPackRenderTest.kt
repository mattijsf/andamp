// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.visualizer.avs

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import nl.mattix.andamp.visualizer.avs.author.AndAmpPack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.PI
import kotlin.math.sin

/**
 * The shipped pack, rendered: every preset through the parser, the evaluator
 * and the engine, fed synthetic music with a kick every [KICK_EVERY] frames.
 *
 * Two checks per preset: [AvsEngine.unimplemented] stays empty, which includes
 * every script compiling, and some frame after the warm-up has at least
 * [MIN_LIT_PIXELS] lit pixels. Snapshots are written to the app's external
 * files dir for a person to look at.
 */
@RunWith(AndroidJUnit4::class)
class AndAmpPackRenderTest {
    @Test
    fun every_preset_in_the_pack_compiles_and_renders_light() {
        val shots = InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir("pack-shots")!!
        shots.listFiles()?.forEach { it.delete() }
        val dead = mutableListOf<String>()

        AndAmpPack.files().forEach { (name, bytes) ->
            val mostLit = brightestFrame(name, bytes, shots)
            if (mostLit < MIN_LIT_PIXELS) dead += "$name (peaked at $mostLit lit pixels)"
        }
        assertTrue("every preset renders light: $dead", dead.isEmpty())
    }

    /**
     * One preset, played for [FRAMES] frames, and how lit its brightest frame
     * after the warm-up was. The snapshot frames are written on the way past.
     */
    private fun brightestFrame(
        name: String,
        bytes: ByteArray,
        shots: File,
    ): Int =
        AvsEngine(WIDTH, HEIGHT).use { engine ->
            engine.load(AvsParser.parse(bytes))
            assertEquals("everything $name uses is implemented", emptyList<String>(), engine.unimplemented)

            val audio = AvsAudio()
            val beat = AvsBeat()
            var mostLit = 0
            for (frame in 0 until FRAMES) {
                audio.feed(synth(frame))
                audio.capture()
                val heard = audio.frame(beat.update(audio.frame(beat = false).waveform))
                val rendered = engine.render(heard)
                if (frame >= WARM_UP) mostLit = maxOf(mostLit, rendered.pixels.count { it != AvsFrame.OPAQUE })
                if (frame in SNAPSHOT_FRAMES) save(rendered, File(shots, "${name.removeSuffix(".avs")}-$frame.png"))
            }
            mostLit
        }

    /** A bass line with a kick every [KICK_EVERY] frames, loud enough to trip the detector. */
    private fun synth(frame: Int): FloatArray {
        val kick = frame % KICK_EVERY < KICK_FRAMES
        return FloatArray(SAMPLES_PER_FRAME) { i ->
            val t = (frame * SAMPLES_PER_FRAME + i) / SAMPLE_RATE
            val bass = 0.30f * sin(2.0 * PI * 55.0 * t).toFloat()
            val tone = 0.18f * sin(2.0 * PI * 220.0 * t).toFloat()
            val boom = if (kick) 0.5f * sin(2.0 * PI * 70.0 * t).toFloat() else 0f
            (bass + tone + boom).coerceIn(-1f, 1f)
        }
    }

    private fun save(
        frame: AvsFrame,
        file: File,
    ) {
        val bitmap = Bitmap.createBitmap(frame.width, frame.height, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(frame.pixels, 0, frame.width, 0, 0, frame.width, frame.height)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private companion object {
        /** What AvsView renders at: MAX_WIDTH wide, 4:3. */
        const val WIDTH = 196
        const val HEIGHT = 147

        const val FRAMES = 240
        const val WARM_UP = 60
        val SNAPSHOT_FRAMES = setOf(80, 150, 220)

        /** Out of the 28,812 pixels of a 196x147 frame. */
        const val MIN_LIT_PIXELS = 300

        const val SAMPLE_RATE = 44100.0
        const val SAMPLES_PER_FRAME = 735
        const val KICK_EVERY = 30
        const val KICK_FRAMES = 4
    }
}
