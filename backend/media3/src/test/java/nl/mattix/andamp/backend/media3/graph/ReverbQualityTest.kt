// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.backend.media3.graph

import nl.mattix.andamp.backend.media3.dsp.DspSettings
import nl.mattix.andamp.backend.media3.dsp.ReverbStage
import nl.mattix.andamp.core.dsp.FrameProcessor
import nl.mattix.andamp.core.model.BuiltInEffects
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * What the reverb graph is judged by.
 *
 * It is compared with `ReverbStage`, a bank of parallel combs: a feedback
 * delay network whose matrix is diagonal, where every line hears only itself
 * and the echo density does not grow. The graph mixes its lines
 * (docs/reverb-notes.md), and has to show more echoes and still a decay.
 */
class ReverbQualityTest {
    private val rate = 44_100

    /** What fraction of Gaussian noise stands outside one standard deviation. */
    private val noiseFraction = 0.3173f

    /** Long enough to average a level over, short enough to see the tail bend. */
    private val window = 4_096
    private val settings = DspSettings.Reverb(enabled = true, level = 1f, size = 0.6f, near = 0f, air = 0.7f)

    private fun graph(): FrameProcessor = BundledPlugins.engine(BuiltInGraphs.reverb(BuiltInEffects.reverb, rate, 2))!!

    /** An impulse response: the tail is what everything here is measured on. */
    private fun response(
        step: (FloatArray) -> Unit,
        frames: Int,
    ): FloatArray {
        val out = FloatArray(frames)
        for (n in 0 until frames) {
            val frame = floatArrayOf(if (n == 0) 1f else 0f, 0f)
            step(frame)
            out[n] = frame[0]
        }
        return out
    }

    private fun graphResponse(frames: Int): FloatArray = response(settled(graph())::process, frames)

    /** The same settings on any engine, so a comparison compares the rooms. */
    private fun settled(engine: FrameProcessor): FrameProcessor {
        BuiltInEffects.reverb.params.forEachIndexed { at, param ->
            engine.setParameter(
                at,
                when (param.id) {
                    "level" -> 1f
                    "size" -> 0.6f
                    "near" -> 0f
                    "air" -> 0.7f
                    else -> param.default
                },
            )
        }
        engine.settleParameters()
        return engine
    }

    private fun stageResponse(frames: Int): FloatArray {
        val stage = ReverbStage(rate).apply { update(settings) }
        return response(stage::process, frames)
    }

    /**
     * Abel and Huang's normalized echo density: how much like noise a stretch
     * of tail is.
     *
     * The fraction of samples outside one standard deviation, over that
     * fraction for Gaussian noise. It reaches 1 when the echoes are dense
     * enough that the tail cannot be told from noise.
     *
     * It measures the distribution of the samples and not their peaks:
     * interpolating a delay read spreads each echo across two samples, which
     * a peak count would score as fewer echoes.
     */
    private fun echoDensity(
        response: FloatArray,
        from: Int,
        until: Int,
    ): Float {
        val window = response.slice(from until until)
        val deviation = kotlin.math.sqrt(window.sumOf { (it * it).toDouble() } / window.size).toFloat()
        if (deviation <= 0f) return 0f
        val outside = window.count { abs(it) > deviation }.toFloat() / window.size
        // erfc(1 / sqrt(2)): the fraction outside one deviation, for noise
        return outside / noiseFraction
    }

    @Test
    fun `mixing the lines puts more echoes in the same tail`() {
        val frames = rate / 2

        val mixed = echoDensity(graphResponse(frames), rate / 20, rate / 4)
        val separate = echoDensity(stageResponse(frames), rate / 20, rate / 4)

        println("echo density in 50..250 ms: mixed $mixed, parallel combs $separate")
        assertTrue("the mixed lines have the higher echo density: $mixed against $separate", mixed > separate)
    }

    /** How many dB the tail falls between two windows, which is a decay rate. */
    private fun slope(
        response: FloatArray,
        from: Int,
        until: Int,
    ): Float {
        fun level(at: Int) = (at until at + window).sumOf { (response[it] * response[it]).toDouble() }.toFloat() / window
        val start = level(from)
        val end = level(until - window)
        return (10f * kotlin.math.log10(start / end)) / ((until - window - from) / rate.toFloat())
    }

    @Test
    fun `the tail decays at one rate`() {
        // one gain shared by lines of different lengths makes T60 follow the
        // length. A decay at one rate is a straight line on a dB scale, so the
        // early slope and the late slope have to agree.
        val response = graphResponse(rate * 2)

        val early = slope(response, rate / 10, rate / 2)
        val late = slope(response, rate / 2, rate)

        val was = stageResponse(rate * 2)
        val wasEarly = slope(was, rate / 10, rate / 2)
        val wasLate = slope(was, rate / 2, rate)

        println("decay: early ${early}dB/s, late ${late}dB/s (was $wasEarly then $wasLate)")
        assertTrue("the early and late slopes agree within 25%: $early against $late", abs(early - late) < early * 0.25f)
        assertTrue(
            "the graph's tail bends less than the parallel combs' tail",
            abs(early - late) / early < abs(wasEarly - wasLate) / wasEarly,
        )
    }

    @Test
    fun `the echoes multiply as the tail goes on`() {
        // the diffusers are inside the loop, so the echoes multiply on every
        // pass; a comb bank's echo rate stays the same
        val response = graphResponse(rate)
        val was = stageResponse(rate)

        val early = echoDensity(response, rate / 50, rate / 20)
        val late = echoDensity(response, rate / 5, rate / 4)
        val wasEarly = echoDensity(was, rate / 50, rate / 20)
        val wasLate = echoDensity(was, rate / 5, rate / 4)

        println("echo density: $early then $late (was $wasEarly then $wasLate)")
        assertTrue(
            "the echo density grows faster than the combs': ${late / early} against ${wasLate / wasEarly}",
            late / early > wasLate / wasEarly,
        )
    }

    @Test
    fun `a decay of two seconds is 60 dB down in about two seconds`() {
        val engine = settled(graph())
        engine.setParameter(BuiltInEffects.reverb.params.indexOfFirst { it.id == "decay" }, 2f)
        engine.settleParameters()
        val response = response(engine::process, rate * 4)

        val perSecond = slope(response, rate / 4, rate * 2)
        val t60 = 60f / perSecond

        println("asked for 2.0 s, measured ${t60}s")
        assertTrue("T60 is within 0.7 s of 2 s: ${t60}s", abs(t60 - 2f) < 0.7f)
    }

    @Test
    fun `the tail decays and stays finite`() {
        val response = graphResponse(rate * 2)

        assertTrue("every sample is finite", response.all { it.isFinite() })
        val early = response.slice(rate / 10 until rate / 5).maxOf { abs(it) }
        val late = response.slice(rate + rate / 2 until rate * 2).maxOf { abs(it) }
        assertTrue("the late tail is below half the early tail: $early, $late", late < early * 0.5f)
        assertTrue("the early tail is not silent", early > 1e-4f)
    }

    /**
     * Five bands, each wide enough to hold the tail's echoes and narrow enough
     * that a tilt across the spectrum does not move it.
     *
     * Flatness over the whole spectrum follows brightness as well as pitch
     * (see SpectralFlatnessTest), so a change that only darkened the tail
     * would read as one that removed pitches.
     */
    private val bands =
        listOf(250.0 to 500.0, 500.0 to 1_000.0, 1_000.0 to 2_000.0, 2_000.0 to 4_000.0, 4_000.0 to 8_000.0)

    private fun wanderResponse(
        wander: Float,
        frames: Int,
    ): FloatArray =
        response(
            settled(BundledPlugins.engine(BuiltInGraphs.reverb(BuiltInEffects.reverb, rate, 2, wander))!!)::process,
            frames,
        )

    /** How flat each band of the tail is, a fifth of a second in. */
    private fun tailFlatness(response: FloatArray): List<Double> {
        val at = rate / 5
        val slice = response.copyOfRange(at, at + window)
        return bands.map { (from, until) -> Spectrum.bandFlatness(slice, rate, from, until) }
    }

    @Test
    fun `mixing the lines evens out the color of the tail`() {
        // echo density says the tail filled in; this says it filled in evenly:
        // the worst band of the mixed room is flatter than the worst band of
        // the combs
        val mixed = tailFlatness(graphResponse(rate))
        val combs = tailFlatness(stageResponse(rate))

        println("band flatness at 200 ms: mixed $mixed, parallel combs $combs")
        assertTrue(
            "the mixed worst band is flatter than the combs' worst band: ${mixed.min()} against ${combs.min()}",
            mixed.min() > combs.min(),
        )
    }

    @Test
    fun `moving the lines does not flatten any band of the tail`() {
        // wander makes no band meaningfully flatter, and the interpolated read
        // it needs is a lowpass inside a feedback loop; see docs/reverb-notes.md
        val still = tailFlatness(wanderResponse(0f, rate))
        val moving = tailFlatness(wanderResponse(2f, rate))

        val gained = still.indices.map { moving[it] - still[it] }
        println("band flatness at 200 ms: still $still, wandering $moving, gained $gained")
        assertTrue("wandering raises no band's flatness by 0.05: $gained", gained.all { it < 0.05 })
    }
}
