// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.baselineprofile

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A cold start of the release build, three ways: compiled as an install without a profile leaves
 * it, with the profile, and compiled whole. Each reports the time to the first frame, which is
 * black, and the time to the player drawn in its skin (time to full display).
 */
@RunWith(AndroidJUnit4::class)
class StartupBenchmark {
    @get:Rule
    val rule = MacrobenchmarkRule()

    /** Nothing compiled ahead of time: the app's code is interpreted and JIT-compiled as it runs. */
    @Test
    fun startWithoutProfile() = start(CompilationMode.None())

    /** The shipped profile compiled, as an install from Play or profileinstaller leaves the app. */
    @Test
    fun startWithProfile() = start(CompilationMode.Partial(BaselineProfileMode.Require))

    /** Everything compiled, to compare the profile with. */
    @Test
    fun startFullyCompiled() = start(CompilationMode.Full())

    private fun start(compilation: CompilationMode) =
        rule.measureRepeated(
            packageName = PACKAGE,
            metrics = listOf(StartupTimingMetric()),
            compilationMode = compilation,
            startupMode = StartupMode.COLD,
            iterations = ITERATIONS,
            setupBlock = { pressHome() },
        ) {
            startPlayer()
        }

    private companion object {
        const val ITERATIONS = 10
    }
}
