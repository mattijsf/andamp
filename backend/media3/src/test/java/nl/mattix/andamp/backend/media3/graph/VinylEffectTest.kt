// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.backend.media3.graph

import nl.mattix.andamp.core.dsp.FrameProcessor
import nl.mattix.andamp.core.dsp.GraphCompiler
import nl.mattix.andamp.core.plugin.PluginLoader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Vinyl, one of docs/examples, measured on test signals against what it says
 * it does.
 *
 * It is larger than the bundled plug-ins: noise sources, a modulated delay
 * line, a filter that changes with age. It is also checked at Mix 0 for
 * returning the track, and at its extreme settings for staying within full
 * scale.
 */
class VinylEffectTest {
    private val rate = 44_100

    private fun engine(
        name: String,
        vararg values: Pair<String, Float>,
    ): FrameProcessor {
        val source = File("../../docs/examples/$name").readText()
        val result = PluginLoader().load(source, sampleRate = rate, channels = 2)
        assertTrue("$name loads: $result", result is PluginLoader.Result.Loaded)
        val plugin = (result as PluginLoader.Result.Loaded).plugin
        val engine = checkNotNull(GraphCompiler.engine(plugin.graph))
        values.forEach { (id, v) ->
            val at = plugin.params.indexOfFirst { it.id == id }
            check(at >= 0) { "$name has a parameter named $id" }
            engine.setParameter(at, v)
        }
        engine.settleParameters()
        return engine
    }

    /** [frames] of stereo out of [engine] for [signal]. */
    private fun run(
        engine: FrameProcessor,
        frames: Int,
        signal: (Int) -> Pair<Float, Float>,
    ): Pair<FloatArray, FloatArray> {
        val left = FloatArray(frames)
        val right = FloatArray(frames)
        for (n in 0 until frames) {
            val (l, r) = signal(n)
            val frame = floatArrayOf(l, r)
            engine.process(frame)
            left[n] = frame[0]
            right[n] = frame[1]
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

    private fun rms(
        x: FloatArray,
        from: Int = 0,
        to: Int = x.size,
    ) = sqrt((from until to).sumOf { x[it].toDouble() * x[it] } / (to - from))

    private fun peak(out: Pair<FloatArray, FloatArray>) = max(out.first.maxOf { abs(it) }, out.second.maxOf { abs(it) })

    /** Loud music: a kick-like low tone, a bright tone, and a burst that comes and goes. */
    private val loud: (Int) -> Pair<Float, Float> = { n ->
        val a = tone(70.0, 0.5)(n)
        val b = tone(1800.0, 0.3)(n) * (if ((n / 5_000) % 2 == 0) 1f else 0.1f)
        val c = tone(7000.0, 0.15)(n)
        (a + b + c) to (a - b + c)
    }

    @Test
    fun `at Mix 0 vinyl returns the track`() {
        listOf("vinyl.lua").forEach { name ->
            val engine = engine(name, "mix" to 0f)
            var worst = 0f
            for (n in 0 until rate) {
                val (l, r) = loud(n)
                val frame = floatArrayOf(l, r)
                engine.process(frame)
                worst = max(worst, max(abs(frame[0] - l), abs(frame[1] - r)))
            }
            assertEquals("$name returns the track unchanged at Mix 0", 0f, worst, 1e-6f)
        }
    }

    @Test
    fun `at its most extreme vinyl stays within full scale`() {
        val extremes =
            mapOf(
                "vinyl.lua" to arrayOf("crackle" to 1f, "wow" to 1f, "age" to 1f, "mix" to 1f),
            )
        extremes.forEach { (name, values) ->
            val out = run(engine(name, *values), frames = rate * 10, signal = loud)
            val highest = peak(out)
            assertTrue("$name stays within full scale: $highest", highest <= 1f)
            assertTrue("$name stays audible and finite", rms(out.first, rate * 9).let { it.isFinite() && it > 0.01 })
        }
    }

    @Test
    fun `a clean record in silence is silent, and a worn one is not`() {
        val silence: (Int) -> Pair<Float, Float> = { 0f to 0f }
        val clean = run(engine("vinyl.lua", "crackle" to 0f, "age" to 0f, "mix" to 1f), rate, silence)
        assertEquals("a mint pressing adds no sound to silence", 0f, peak(clean), 1e-6f)

        val worn = run(engine("vinyl.lua", "crackle" to 1f, "age" to 0f, "mix" to 1f), rate * 5, silence)
        val floor = rms(worn.first)
        assertTrue("the surface is heard: $floor", floor > 0.001 && floor < 0.02)
        // crackle is not a hiss: the ticks stand well above the bed they sit in
        assertTrue("the ticks stand ten times above the bed: ${peak(worn)} against $floor", peak(worn) > 10 * floor)
        val apart = rms(FloatArray(worn.first.size) { worn.first[it] - worn.second[it] })
        assertTrue("each channel has its own noise: $apart", apart > floor)
    }

    @Test
    fun `the record keeps the bass in the middle, the way a groove is cut`() {
        fun bass(phase: Float): Double {
            val engine = engine("vinyl.lua", "crackle" to 0f, "wow" to 0f, "age" to 0f, "mix" to 1f)
            val signal: (Int) -> Pair<Float, Float> = { n -> tone(60.0, 0.4)(n).let { it to it * phase } }
            val (left, right) = run(engine, rate * 2, signal)
            return rms(FloatArray(left.size) { left[it] - right[it] }, rate)
        }

        val together = bass(1f)
        val opposed = bass(-1f)
        assertTrue("in-phase bass stays centered: $together", together < 0.01)
        assertTrue("most of the out-of-phase bass is removed: $opposed", opposed < 0.8 * 0.4 * sqrt(2.0) / 2)
    }

    @Test
    fun `an old pressing has lost its top and gained a rumble`() {
        fun through(
            hz: Double,
            age: Float,
        ): Double {
            val engine = engine("vinyl.lua", "crackle" to 0f, "wow" to 0f, "age" to age, "mix" to 1f)
            return rms(run(engine, rate, { n -> tone(hz, 0.4)(n).let { it to it } }).first, rate / 2)
        }

        val mint = through(12_000.0, 0f)
        // a mint pressing keeps most of 12 kHz
        assertTrue("12 kHz survives a mint pressing: $mint", mint > 0.15)
        assertTrue("an old pressing loses most of 12 kHz", through(12_000.0, 1f) < mint / 5)
        // the lowpass takes 8 kHz as well
        assertTrue("an old pressing loses most of 8 kHz", through(8_000.0, 1f) < through(8_000.0, 0f) / 10)
        assertTrue("an old pressing keeps 1 kHz", through(1_000.0, 1f) > through(1_000.0, 0f) / 2)

        val quiet = run(engine("vinyl.lua", "crackle" to 0f, "age" to 1f, "mix" to 1f), rate * 2, { 0f to 0f })
        assertTrue("the motor is heard under it: ${rms(quiet.first)}", rms(quiet.first, rate) > 0.0005)
    }

    @Test
    fun `an off-center hole sways the pitch`() {
        // the first stretch against the second: the pitch is low and then
        // high. Over a whole turn it evens out, so the two halves are counted
        // separately.
        fun halves(wow: Float): Pair<Int, Int> {
            val engine = engine("vinyl.lua", "crackle" to 0f, "wow" to wow, "age" to 0f, "mix" to 1f)
            val quarter = (rate * 0.45).toInt()
            // the line has to fill before anything comes out of it, so the
            // first cycles are skipped
            val settle = rate / 10
            val out = run(engine, settle + quarter * 2, { n -> tone(1000.0, 0.4)(n).let { it to it } }).first

            fun cycles(from: Int) = (from + 1 until from + quarter).count { out[it - 1] <= 0f && out[it] > 0f }
            return cycles(settle) to cycles(settle + quarter)
        }

        val (trueA, trueB) = halves(0f)
        assertTrue("a centered hole holds the pitch: $trueA then $trueB", abs(trueA - trueB) <= 1)
        val (slow, fast) = halves(1f)
        assertTrue("an off-center hole sways the pitch: $slow then $fast", fast - slow >= 2)
    }

    @Test
    fun `a worn groove hands each side over to the other`() {
        fun separation(age: Float): Double {
            val engine = engine("vinyl.lua", "crackle" to 0f, "wow" to 0f, "age" to age, "mix" to 1f)
            val onlyLeft: (Int) -> Pair<Float, Float> = { n -> tone(1000.0, 0.3)(n) to 0f }
            val (left, right) = run(engine, rate, onlyLeft)
            return rms(right, rate / 2) / rms(left, rate / 2)
        }

        val mint = separation(0f)
        val worn = separation(1f)
        assertTrue("a new pressing keeps the sides apart: $mint", mint < 0.15)
        assertTrue("a worn pressing mixes the sides: $worn", worn > 2 * mint)
    }

    @Test
    fun `a stylus that cannot follow the groove rounds it off`() {
        fun harmonic(age: Float): Double {
            val engine = engine("vinyl.lua", "crackle" to 0f, "wow" to 0f, "age" to age, "mix" to 1f)
            val out = run(engine, rate, { n -> tone(300.0, 0.7)(n).let { it to it } }).first
            val third = level(out, 900.0)
            return third / level(out, 300.0)
        }

        val mint = harmonic(0f)
        val worn = harmonic(1f)
        assertTrue("a mint pressing traces it: $mint", mint < 0.05)
        assertTrue("an old pressing adds the third harmonic: $worn", worn > 2 * mint)
    }

    @Test
    fun `every half minute the stylus crosses a scratch you cannot miss`() {
        val engine = engine("vinyl.lua", "crackle" to 0.4f, "wow" to 0f, "age" to 0f, "mix" to 1f)
        val loudest = DoubleArray(40)
        for (n in 0 until rate * 40) {
            val frame = floatArrayOf(0f, 0f)
            engine.process(frame)
            loudest[n / rate] = max(loudest[n / rate], max(abs(frame[0]), abs(frame[1])).toDouble())
        }

        // the window opens at the top of a 30 s sine, a quarter of the way in
        val scratched = setOf(7, 37)
        val rest = loudest.filterIndexed { second, _ -> second !in scratched }.max()
        // the ticks themselves are loud, so the scratch has to stand clear of
        // them
        scratched.forEach { second ->
            assertTrue(
                "a scratch at $second s stands above the ticks: ${loudest[second]} over $rest",
                loudest[second] > 1.5 * rest,
            )
        }
    }
}
