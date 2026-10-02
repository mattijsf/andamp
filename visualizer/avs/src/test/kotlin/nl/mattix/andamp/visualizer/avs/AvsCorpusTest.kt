// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.visualizer.avs

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The parser against real presets.
 *
 * Other people's presets carry their authors' terms (NOTICE.md) and are not in
 * this repository, so this skips unless pointed at a directory:
 *
 * ```bash
 * ANDAMP_AVS_CORPUS=~/avs-presets ./gradlew :visualizer:avs:test
 * ```
 *
 * The corpus this was written against is Winamp's own built-in set, 156 presets
 * from `default_presets.7z` in the VISBOT archive, of which the Python census
 * in docs/avs-census.py frames 155. This asserts the same share.
 */
class AvsCorpusTest {
    private val corpus: File? =
        System.getenv(CORPUS_ENV)?.let { File(it) }?.takeIf { it.isDirectory }

    private fun presets(): List<File> =
        corpus!!
            .walkTopDown()
            .filter { it.isFile && it.extension.equals("avs", ignoreCase = true) }
            .sorted()
            .toList()

    @Test
    fun `at least 99 percent of the presets frame`() {
        assumeTrue("set $CORPUS_ENV to a directory of .avs files", corpus != null)
        val files = presets()
        assumeTrue("no .avs files under $corpus", files.isNotEmpty())

        val failures =
            files.mapNotNull { file ->
                runCatching { AvsParser.parse(file.readBytes()) }
                    .exceptionOrNull()
                    ?.let { file.name to (it.message ?: it::class.simpleName) }
            }

        val framed = files.size - failures.size
        val share = framed.toDouble() / files.size
        assertTrue(
            "at least 99% of the presets frame: $framed/${files.size}; " +
                failures.joinToString("; ") { "${it.first}: ${it.second}" },
            share >= MINIMUM_SHARE,
        )
    }

    /**
     * A preset full of Unknown(id) frames without saying whether it could run,
     * so the share of unknown components is bounded too.
     */
    @Test
    fun `at most one percent of the components are unknown`() {
        assumeTrue("set $CORPUS_ENV to a directory of .avs files", corpus != null)
        val files = presets()
        assumeTrue("no .avs files under $corpus", files.isNotEmpty())

        val components =
            files.flatMap { file ->
                runCatching { AvsParser.parse(file.readBytes()).flatten() }.getOrDefault(emptyList())
            }
        val unknown = components.filterIsInstance<AvsComponent.Unknown>()

        assertTrue(
            "at most 1% of the components are unknown; unknown ids: " + unknown.map { it.id }.distinct().sorted(),
            unknown.size.toDouble() / components.size <= MAXIMUM_UNKNOWN,
        )
    }

    /**
     * Prints how many of the corpus presets this build can run and the most
     * common missing components. It goes by component name, because building
     * renderers needs the evaluator and so a device. Only a count above zero
     * is asserted.
     */
    @Test
    fun `this build can run some of the corpus`() {
        assumeTrue("set $CORPUS_ENV to a directory of .avs files", corpus != null)
        val files = presets()
        assumeTrue("no .avs files under $corpus", files.isNotEmpty())

        val parsed = files.mapNotNull { runCatching { AvsParser.parse(it.readBytes()) }.getOrNull() }
        val runnable = parsed.count { AvsEngine.canRun(it) }
        val missing =
            parsed
                .flatMap { it.flatten() }
                .map { it.name }
                .filterNot { it == "Effect List" || it in AvsEngine.supported }
                .groupingBy { it }
                .eachCount()
                .entries
                .sortedByDescending { it.value }

        println("AVS corpus: $runnable of ${parsed.size} presets run with nothing missing")
        println("AVS corpus: still wanted, most common first:")
        missing.take(TOP_MISSING).forEach { println("  ${it.key}: ${it.value}") }

        assertTrue("this build runs at least one corpus preset", runnable > 0)
    }

    /**
     * A component whose read() succeeds can still throw in render(), so every
     * runnable preset that needs no evaluator is rendered for two frames on a
     * small frame. The scripted ones need the native library and are exercised
     * on device by AvsRealPresetTest.
     */
    @Test
    fun `every runnable preset without code renders two frames`() {
        assumeTrue("set $CORPUS_ENV to a directory of .avs files", corpus != null)
        val files = presets()
        assumeTrue("no .avs files under $corpus", files.isNotEmpty())

        val candidates =
            files
                .mapNotNull { file ->
                    runCatching { file.name to AvsParser.parse(file.readBytes()) }.getOrNull()
                }.filter { (_, preset) ->
                    AvsEngine.canRun(preset) &&
                        preset.flatten().none { it.name in SCRIPTED }
                }
        assumeTrue("no evaluator-free runnable presets in the corpus", candidates.isNotEmpty())

        val crashed =
            candidates.mapNotNull { (name, preset) ->
                runCatching {
                    AvsEngine(WIDTH, HEIGHT).use { engine ->
                        engine.load(preset)
                        engine.render(AvsAudioFrame(beat = true))
                        engine.render(AvsAudioFrame())
                    }
                }.exceptionOrNull()?.let { "$name: $it" }
            }

        println("AVS corpus: rendered ${candidates.size - crashed.size} of ${candidates.size} evaluator-free presets")
        assertTrue(crashed.joinToString("; "), crashed.isEmpty())
    }

    private companion object {
        /** How many missing components are printed. */
        const val TOP_MISSING = 12

        const val CORPUS_ENV = "ANDAMP_AVS_CORPUS"

        /** 155 of Winamp's 156 built-in presets frame. */
        const val MINIMUM_SHARE = 0.99

        /** Room for a stray unknown id. */
        const val MAXIMUM_UNKNOWN = 0.01

        const val WIDTH = 64
        const val HEIGHT = 48

        /** The components whose renderers compile ns-eel, which needs a device. */
        val SCRIPTED =
            setOf(
                "Super Scope",
                "Movement",
                "Dynamic Movement",
                "Dynamic Shift",
                "Dynamic Distance Modifier",
                "Color Modifier",
            )
    }
}
