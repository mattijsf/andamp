// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.visualizer.avs

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Every component's code runs in its own variable pool, the way AVS's ns-eel
 * gives each component its own VM: a variable one scope assigns reads as zero
 * in another, and scopes that use the same variable names do not affect each
 * other.
 */
@RunWith(AndroidJUnit4::class)
class SharedEelTest {
    @Test
    fun one_scopes_variable_does_not_leak_into_the_next() {
        val setter =
            scopeComponent(
                id = 1,
                init = "n=1;",
                perFrame = "q=0.5;",
                perPoint = "x=-1;y=-1;",
            )
        val reader =
            scopeComponent(
                id = 2,
                init = "n=8;",
                perFrame = "",
                // y from a variable only the other scope assigns: stays zero here
                perPoint = "x=i*2-1;y=q;",
            )

        AvsEngine(32, 32).use { engine ->
            engine.load(AvsPreset(clearEveryFrame = true, components = listOf(setter, reader)))
            engine.render()

            val rows = (0 until 32).filter { y -> (0 until 32).any { x -> engine.frame[x, y] != AvsFrame.OPAQUE } }
            assertTrue("the scopes draw something", rows.isNotEmpty())
            // y = 0 is the center row; the setter's q must not have moved it
            assertEquals("q stays in its own component's pool", 16, rows.max())
        }
    }

    /** Four scopes each increment a rotation in their frame section; each advances its own. */
    @Test
    fun rotation_rates_stay_per_component() {
        val scopes =
            (0 until 4).map {
                scopeComponent(
                    id = 10 + it,
                    init = "rz=0;rdz=1;n=2;",
                    perFrame = "rz=rz+rdz;",
                    perPoint = "x=i*2-1;y=rz/90;",
                )
            }
        AvsEngine(64, 64).use { engine ->
            engine.load(AvsPreset(clearEveryFrame = true, components = scopes))
            repeat(30) { engine.render() }

            // after 30 frames every scope's own rz is 30 -> y = 1/3 -> row ~42;
            // one shared pool would have rz = 120 and pin the lines to the bottom
            val rows = (0 until 64).filter { y -> (0 until 64).any { x -> engine.frame[x, y] != AvsFrame.OPAQUE } }
            assertTrue("the scopes draw something", rows.isNotEmpty())
            assertTrue("each scope advances its own rotation, rows=$rows", rows.max() in 40..44)
        }
    }

    /** `n` is per scope too: one scope's point count does not become the other's. */
    @Test
    fun each_scope_keeps_its_own_point_count() {
        // both draw dots along their own row; the second asks for far fewer
        val many = scopeComponent(id = 1, init = "n=32;", perFrame = "", perPoint = "x=i*2-1;y=-0.5;", lines = false)
        val few = scopeComponent(id = 2, init = "n=2;", perFrame = "", perPoint = "x=i*2-1;y=0.5;", lines = false)

        AvsEngine(64, 32).use { engine ->
            engine.load(AvsPreset(clearEveryFrame = true, components = listOf(many, few)))
            repeat(3) { engine.render() }

            val manyRow = (0 until 64).count { x -> engine.frame[x, 8] != AvsFrame.OPAQUE }
            val fewRow = (0 until 64).count { x -> engine.frame[x, 23] != AvsFrame.OPAQUE }
            assertTrue("the 32-point scope lights many columns: $manyRow", manyRow >= 16)
            assertTrue("the 2-point scope stays sparse: $fewRow", fewRow <= 4)
        }
    }

    /** A minimal current-layout Super Scope body with the four code sections. */
    private fun scopeComponent(
        id: Int,
        init: String,
        perFrame: String,
        perPoint: String,
        lines: Boolean = true,
    ): AvsComponent.Builtin {
        var body = byteArrayOf(1)
        // stored order: per point, per frame, on beat, init
        listOf(perPoint, perFrame, "", init).forEach { body += int32(it.length) + it.toByteArray(Charsets.ISO_8859_1) }
        body += int32(0) // flags: waveform, left channel
        body += int32(1) + int32(0x00FFFFFF) // one color, white
        body += int32(if (lines) 1 else 0)
        return AvsComponent.Builtin(SUPER_SCOPE, checkNotNull(AvsComponents[SUPER_SCOPE]), body + int32(id))
    }

    private fun int32(value: Int) =
        byteArrayOf(
            value.toByte(),
            (value shr 8).toByte(),
            (value shr 16).toByte(),
            (value shr 24).toByte(),
        )

    private companion object {
        const val SUPER_SCOPE = 36
    }
}
