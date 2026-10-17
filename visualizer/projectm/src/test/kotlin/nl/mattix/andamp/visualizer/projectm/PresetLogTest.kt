// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.visualizer.projectm

import org.junit.Assert.assertEquals
import org.junit.Test

/** The lines are projectM 4.1's own, from MilkdropPreset, FinalComposite and PerPixelMesh. */
class PresetLogTest {
    private fun loading(path: String) = "[Preset] Loading preset from file \"$path\"."

    @Test
    fun `a shader that falls back is left out of the preset loading`() {
        val log = PresetLog()

        val grew =
            log.read(
                listOf(
                    loading("/p/a.milk"),
                    "[Warp Shader] Error compiling warp shader code:ERROR: 0:12: 'x' : undeclared identifier",
                    "[Composite Shader] Error compiling composite warp shader code:ERROR: 0:3: syntax error",
                    "[Composite Shader] Using fallback shader.",
                ),
            )

        assertEquals(setOf("/p/a.milk"), grew)
        assertEquals(listOf("warp shader", "composite shader"), log.leftOut("/p/a.milk"))
    }

    @Test
    fun `code, waves and shapes are named, once each`() {
        val log = PresetLog()

        log.read(
            listOf(
                loading("/p/a.milk"),
                "[Preset] Could not compile per-frame code: unexpected token(L1 C4)",
                "[Preset] Could not compile per-pixel code: unexpected token(L2 C1)",
                "[Preset] Could not compile custom wave 2 per-point code: x(L1 C1)",
                "[Preset] Could not compile custom shape 0 per-frame code: x(L1 C1)",
                "[Preset] Could not compile custom shape 0 per-frame INIT code: x(L1 C1)",
            ),
        )

        assertEquals(
            listOf("per-frame code", "per-pixel code", "custom wave 2", "custom shape 0"),
            log.leftOut("/p/a.milk"),
        )
    }

    @Test
    fun `each line belongs to the preset loading before it`() {
        val log = PresetLog()

        log.read(listOf(loading("/p/a.milk"), WARP_ERROR, loading("/p/b.milk"), COMPOSITE_ERROR))

        assertEquals(listOf("warp shader"), log.leftOut("/p/a.milk"))
        assertEquals(listOf("composite shader"), log.leftOut("/p/b.milk"))
    }

    @Test
    fun `a preset loaded again is read afresh, and what loads cleanly leaves nothing out`() {
        val log = PresetLog()
        log.read(listOf(loading("/p/a.milk"), WARP_ERROR))

        val grew =
            log.read(
                listOf(
                    loading("/p/a.milk"),
                    "[Warp Shader] Loaded preset warp shader code.",
                    "[Warp Shader] Successfully compiled warp shader code.",
                ),
            )

        assertEquals(emptySet<String>(), grew)
        assertEquals(emptyList<String>(), log.leftOut("/p/a.milk"))
    }

    @Test
    fun `lines before any preset are no preset's`() {
        val log = PresetLog()

        assertEquals(emptySet<String>(), log.read(listOf(WARP_ERROR)))
    }

    private companion object {
        const val WARP_ERROR = "[Warp Shader] Error compiling warp shader code:ERROR: 0:1: syntax error"
        const val COMPOSITE_ERROR = "[Composite Shader] Error compiling composite warp shader code:ERROR: 0:1: syntax error"
    }
}
