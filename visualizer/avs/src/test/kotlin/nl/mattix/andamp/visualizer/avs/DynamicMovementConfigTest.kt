// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.visualizer.avs

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Dynamic Movement's body on the JVM: the renderer needs the evaluator, the
 * parse does not. These cover the grid size, for which AVS accepts a single
 * cell (`max(2, grid + 1)` vertices).
 */
class DynamicMovementConfigTest {
    @Test
    fun `a one-cell grid survives the parse`() {
        val config = DynamicMovementRenderer.read(body(gridWidth = 1, gridHeight = 1))!!

        assertEquals(1, config.gridWidth)
        assertEquals(1, config.gridHeight)
    }

    @Test
    fun `an oversized grid is capped at 512`() {
        val config = DynamicMovementRenderer.read(body(gridWidth = 1_000_000, gridHeight = 64))!!

        assertEquals(512, config.gridWidth)
        assertEquals(64, config.gridHeight)
    }

    private fun body(
        gridWidth: Int,
        gridHeight: Int,
    ): ByteArray {
        var out = byteArrayOf(1)
        listOf("x = x;", "", "", "").forEach { out += sized(it) }
        return out + int32(0) + int32(1) + int32(gridWidth) + int32(gridHeight) +
            int32(0) + int32(0) + int32(0) + int32(0)
    }

    private fun int32(value: Int) =
        byteArrayOf(
            value.toByte(),
            (value shr 8).toByte(),
            (value shr 16).toByte(),
            (value shr 24).toByte(),
        )

    private fun sized(text: String) = int32(text.length) + text.toByteArray(Charsets.ISO_8859_1)
}
