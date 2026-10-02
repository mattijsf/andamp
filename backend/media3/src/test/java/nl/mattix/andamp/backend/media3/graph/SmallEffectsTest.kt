// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.backend.media3.graph

import nl.mattix.andamp.core.dsp.FrameProcessor
import nl.mattix.andamp.core.dsp.GraphCompiler
import nl.mattix.andamp.core.plugin.PluginLoader
import nl.mattix.andamp.core.plugin.PluginSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sin

/**
 * The effects that ship, measured on test tones against what each says it
 * does.
 *
 * Each is checked at its neutral setting for returning its input, at its
 * extreme for staying under full scale, and for the one thing it does.
 */
class SmallEffectsTest {
    private val rate = 44_100

    private fun load(
        name: String,
        at: Int = rate,
        channels: Int = 2,
    ): PluginSpec {
        val source = checkNotNull(javaClass.getResourceAsStream("/plugins/$name")).bufferedReader().readText()
        val result = PluginLoader().load(source, sampleRate = at, channels = channels)
        assertTrue("$name loads at $at Hz: $result", result is PluginLoader.Result.Loaded)
        return (result as PluginLoader.Result.Loaded).plugin
    }

    private fun engine(
        name: String,
        vararg values: Pair<String, Float>,
    ): FrameProcessor {
        val plugin = load(name)
        val engine = checkNotNull(GraphCompiler.engine(plugin.graph))
        values.forEach { (id, v) ->
            val at = plugin.params.indexOfFirst { it.id == id }
            check(at >= 0) { "$name has a parameter named $id" }
            engine.setParameter(at, v)
        }
        return engine
    }

    /** [frames] of stereo out of [engine], after a second to let smoothing and envelopes settle. */
    private fun run(
        engine: FrameProcessor,
        frames: Int = 16_384,
        settle: Int = rate,
        signal: (Int) -> Pair<Float, Float>,
    ): Pair<FloatArray, FloatArray> {
        val left = FloatArray(frames)
        val right = FloatArray(frames)
        for (n in 0 until settle + frames) {
            val (l, r) = signal(n)
            val frame = floatArrayOf(l, r)
            engine.process(frame)
            if (n >= settle) {
                left[n - settle] = frame[0]
                right[n - settle] = frame[1]
            }
        }
        return left to right
    }

    private fun tone(
        hz: Double,
        amplitude: Double,
    ): (Int) -> Float = { n -> (amplitude * sin(2 * PI * hz * n / rate)).toFloat() }

    /** How strong [hz] is in [x], as an amplitude: a Hann-windowed projection. */
    private fun level(
        x: FloatArray,
        hz: Double,
    ): Double {
        var re = 0.0
        var im = 0.0
        var weight = 0.0
        for (n in x.indices) {
            val w = 0.5 - 0.5 * cos(2 * PI * n / x.size)
            re += x[n] * w * cos(2 * PI * hz * n / rate)
            im += x[n] * w * sin(2 * PI * hz * n / rate)
            weight += w
        }
        return 2 * hypot(re, im) / weight
    }

    private fun db(ratio: Double) = 20 * log10(ratio)

    /** The worst difference between what went in and what came out, past settling. */
    private fun untouched(
        engine: FrameProcessor,
        signal: (Int) -> Pair<Float, Float>,
    ): Float {
        var worst = 0f
        for (n in 0 until rate * 2) {
            val (l, r) = signal(n)
            val frame = floatArrayOf(l, r)
            engine.process(frame)
            if (n > rate) worst = max(worst, max(abs(frame[0] - l), abs(frame[1] - r)))
        }
        return worst
    }

    private fun peak(out: Pair<FloatArray, FloatArray>) = max(out.first.maxOf { abs(it) }, out.second.maxOf { abs(it) })

    /** Loud music: two tones near full scale, different in each channel. */
    private val loud: (Int) -> Pair<Float, Float> = { n ->
        val a = tone(90.0, 0.55)(n)
        val b = tone(5000.0, 0.4)(n)
        (a + b) to (a - b)
    }

    /** The plug-ins folder and [BundledPlugins.FILES] hold the same files. */
    @Test
    fun `every file in the plug-ins folder is one that ships, and every one that ships is there`() {
        val folder =
            java.io
                .File("src/main/resources/plugins")
                .list()
                .orEmpty()
                .filter { it.endsWith(".lua") }
        assertEquals(BundledPlugins.FILES.sorted(), folder.sorted())
    }

    @Test
    fun `every effect that ships loads at every rate a phone plays at, in mono and stereo`() {
        listOf("preamp.lua", "tone.lua", "virtualbass.lua", "width.lua", "leveler.lua", "crossfeed.lua").forEach { name ->
            listOf(44_100, 48_000, 22_050, 96_000).forEach { at ->
                listOf(1, 2).forEach { channels ->
                    val plugin = load(name, at, channels)
                    assertTrue("$name has at least one control", plugin.params.isNotEmpty())
                    assertTrue("$name has at most two controls", plugin.params.size <= 2)
                }
            }
        }
    }

    @Test
    fun `each one at its neutral setting returns the track to within 1e-4`() {
        val neutral =
            listOf(
                engine("preamp.lua"),
                engine("tone.lua"),
                engine("virtualbass.lua", "amount" to 0f),
                engine("width.lua"),
                engine("leveler.lua", "amount" to 0f),
                engine("crossfeed.lua", "amount" to 0f),
            )
        // one part in ten thousand, -80 dB: the shelves of Bass & Treble at
        // 0 dB are the identity only to float rounding in their coefficients;
        // the others are exact
        neutral.forEachIndexed { i, e ->
            val changed = untouched(e, loud)
            assertTrue("effect $i returns a loud track to within 1e-4: $changed", changed < 1e-4f)
        }
    }

    /** The rack keeps the Preamp first by this id, so the file has to carry it. */
    @Test
    fun `the preamp the rack keeps first is the one that ships`() {
        assertEquals(nl.mattix.andamp.core.model.BuiltInEffects.PREAMP, load("preamp.lua").id)
    }

    @Test
    fun `preamp changes the whole track by its gain, in either direction`() {
        listOf(-6f, 6f, 12f).forEach { gain ->
            val out =
                run(engine("preamp.lua", "gain" to gain)) { n ->
                    (tone(100.0, 0.05)(n) + tone(8000.0, 0.05)(n)).let {
                        it to
                            it
                    }
                }
            assertEquals("100 Hz at $gain dB", gain.toDouble(), db(level(out.first, 100.0) / 0.05), 0.05)
            assertEquals("8 kHz at $gain dB", gain.toDouble(), db(level(out.first, 8000.0) / 0.05), 0.05)
        }
    }

    @Test
    fun `bass and treble turns the bass and the treble against the middle`() {
        val bass = run(engine("tone.lua", "bass" to 12f)) { n -> (tone(40.0, 0.05)(n) + tone(1000.0, 0.05)(n)).let { it to it } }
        val bassOverMiddle = db(level(bass.first, 40.0) / level(bass.first, 1000.0))
        assertEquals("40 Hz stands nearly the full 12 dB over 1 kHz", 12.0, bassOverMiddle, 1.0)

        val treble =
            run(engine("tone.lua", "treble" to 12f)) { n ->
                (tone(16_000.0, 0.05)(n) + tone(1000.0, 0.05)(n)).let {
                    it to
                        it
                }
            }
        val trebleOverMiddle = db(level(treble.first, 16_000.0) / level(treble.first, 1000.0))
        assertEquals("16 kHz stands nearly the full 12 dB over 1 kHz", 12.0, trebleOverMiddle, 1.0)
    }

    /**
     * Bass & Treble, Width and Crossfeed scale their output down by what they
     * add, and do not pass the peak they were given. Virtual Bass and Leveler
     * hold the peak under -1 dBFS with a look-ahead limiter.
     */
    @Test
    fun `nothing turned to the top gets near full scale`() {
        val inputPeak = 0.95f
        val loudest: (Int) -> Pair<Float, Float> = { n ->
            val a = tone(60.0, 0.5)(n)
            val b = tone(5000.0, 0.45)(n)
            (a + b) to (a - b)
        }
        listOf(
            "Bass & Treble" to engine("tone.lua", "bass" to 12f, "treble" to 12f),
            "Width" to engine("width.lua", "width" to 2f),
            "Crossfeed" to engine("crossfeed.lua", "amount" to 1f),
        ).forEach { (name, e) ->
            val p = peak(run(e, signal = loudest))
            assertTrue("$name does not pass the input peak $inputPeak: $p", p <= inputPeak * 1.02f)
        }
        listOf(
            "Virtual Bass" to engine("virtualbass.lua", "amount" to 1f),
            "Leveler" to engine("leveler.lua", "amount" to 1f),
        ).forEach { (name, e) ->
            val p = peak(run(e, signal = loudest))
            assertTrue("$name stays at or below 0.9: $p", p <= 0.9f)
        }
    }

    /** The limiter changes the gain; it does not reshape the wave as a clipper does. */
    @Test
    fun `holding a loud tone under full scale adds no distortion`() {
        listOf("virtualbass.lua", "leveler.lua").forEach { name ->
            val out = run(engine(name, "amount" to 1f)) { n -> tone(1000.0, 0.99)(n).let { it to it } }.first
            val fundamental = level(out, 1000.0)
            assertTrue("$name holds the peak at or below 0.9: ${out.maxOf { abs(it) }}", out.maxOf { abs(it) } <= 0.9f)
            listOf(2000.0, 3000.0, 5000.0).forEach { hz ->
                val distortion = db(level(out, hz) / fundamental)
                assertTrue("$name adds less than -60 dB at $hz Hz: $distortion dB", distortion < -60)
            }
        }
    }

    /** A hit after a quiet stretch, while the leveler still has the quiet part turned up. */
    @Test
    fun `a sudden loud hit after a quiet passage is caught before it lands`() {
        val e = engine("leveler.lua", "amount" to 1f)
        val quietThenHit: (Int) -> Pair<Float, Float> = { n ->
            val quiet = tone(440.0, 0.02)(n)
            val hit = if (n >= rate * 3) tone(90.0, 0.95)(n) else 0f
            (quiet + hit).let { it to it }
        }
        val out = run(e, frames = rate, settle = rate * 3 - rate / 10, signal = quietThenHit)
        assertTrue("the hit is held at or below 0.9: ${peak(out)}", peak(out) <= 0.9f)
    }

    @Test
    fun `virtual bass adds overtones a small speaker can play, and nothing above them`() {
        val out = run(engine("virtualbass.lua", "amount" to 1f)) { n -> tone(60.0, 0.25)(n).let { it to it } }.first
        val fundamental = level(out, 60.0)
        assertTrue("an overtone at 180 Hz is added", db(level(out, 180.0) / fundamental) > -30)
        assertTrue("an overtone at 300 Hz is added", db(level(out, 300.0) / fundamental) > -40)
        listOf(2_100.0, 4_980.0, 9_000.0, 15_000.0).forEach { hz ->
            assertTrue("nothing reaches $hz Hz: ${db(level(out, hz) / fundamental)} dB", db(level(out, hz) / fundamental) < -70)
        }
    }

    /** 5 ms late, since the limiter looks that far ahead, and otherwise unchanged. */
    @Test
    fun `virtual bass leaves a voice and a cymbal as they were`() {
        val out =
            run(engine("virtualbass.lua", "amount" to 1f)) { n ->
                (tone(1000.0, 0.3)(n) + tone(8000.0, 0.2)(n)).let {
                    it to it
                }
            }.first
        assertEquals(0.3, level(out, 1000.0), 0.003)
        assertEquals(0.2, level(out, 8000.0), 0.002)
        assertTrue("nothing added below them: ${db(level(out, 300.0) / 0.3)} dB", db(level(out, 300.0) / 0.3) < -60)
    }

    @Test
    fun `width at zero is mono, and wider pushes the sides apart without touching the middle`() {
        val sides: (Int) -> Pair<Float, Float> = { n -> (tone(500.0, 0.2)(n) + tone(700.0, 0.2)(n)) to tone(500.0, 0.2)(n) }
        val mono = run(engine("width.lua", "width" to 0f), signal = sides)
        assertTrue("both speakers play the same", mono.first.indices.all { abs(mono.first[it] - mono.second[it]) < 1e-6f })

        // at 200 % both channels are scaled to 2/3
        val wide = run(engine("width.lua", "width" to 2f), signal = sides)
        val shared = level(wide.first, 500.0)
        assertEquals("the left-only 700 Hz is 1.5 times the shared 500 Hz", 1.5, level(wide.first, 700.0) / shared, 0.03)
        assertEquals(
            "the left-only 700 Hz appears on the right at half the shared level",
            0.5,
            level(wide.second, 700.0) / shared,
            0.03,
        )
    }

    @Test
    fun `leveler brings a quiet passage up and a loud one down, and leaves silence alone`() {
        val quiet = run(engine("leveler.lua", "amount" to 1f), settle = rate * 3) { n -> tone(440.0, 0.01)(n).let { it to it } }
        val quietGain = db(level(quiet.first, 440.0) / 0.01)
        assertTrue("-40 dBFS is raised: $quietGain dB", quietGain > 8)

        val loudOut = run(engine("leveler.lua", "amount" to 1f), settle = rate * 3) { n -> tone(440.0, 0.7)(n).let { it to it } }
        val loudGain = db(level(loudOut.first, 440.0) / 0.7)
        assertTrue("-3 dBFS is lowered: $loudGain dB", loudGain < -5)

        val hiss = run(engine("leveler.lua", "amount" to 1f), settle = rate * 3) { n -> tone(440.0, 0.0003)(n).let { it to it } }
        val hissGain = db(level(hiss.first, 440.0) / 0.0003)
        assertTrue("-70 dBFS between songs is not raised: $hissGain dB", hissGain < 1)
    }

    @Test
    fun `crossfeed puts bass from one side in the other ear, and much less treble`() {
        val out = run(engine("crossfeed.lua", "amount" to 1f)) { n -> (tone(100.0, 0.3)(n) + tone(8000.0, 0.3)(n)) to 0f }
        val lowAcross = db(level(out.second, 100.0) / level(out.first, 100.0))
        val highAcross = db(level(out.second, 8000.0) / level(out.first, 8000.0))
        assertEquals("100 Hz reaches the other ear 6 dB down", -6.0, lowAcross, 1.0)
        assertTrue("8 kHz reaches the other ear more than 30 dB down: $highAcross dB", highAcross < -30)
    }

    @Test
    fun `crossfeed keeps centered bass as loud as it was`() {
        val out = run(engine("crossfeed.lua", "amount" to 1f)) { n -> tone(80.0, 0.3)(n).let { it to it } }
        assertEquals(0.0, db(level(out.first, 80.0) / 0.3), 0.5)
    }
}
