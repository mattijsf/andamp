// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.visualizer.projectm

import android.opengl.GLES30
import java.nio.ByteBuffer

/**
 * Looks at the frame just drawn, on the render thread: three rows and three columns, at a
 * quarter, half and three quarters of the way across. Reading the whole frame back would
 * stall the GPU for longer, and a preset that draws anything reaches one of these lines.
 */
internal class BlackProbe {
    private var buffer: ByteBuffer? = null

    /** Whether every pixel on the lines is black, or nearly. */
    fun isBlack(
        width: Int,
        height: Int,
    ): Boolean {
        if (width <= 0 || height <= 0) return false
        val need = maxOf(width, height) * BYTES_PER_PIXEL
        val pixels = buffer?.takeIf { it.capacity() >= need } ?: ByteBuffer.allocateDirect(need).also { buffer = it }
        return FRACTIONS.all { at ->
            black(pixels, 0, (height * at).toInt(), width, 1) && black(pixels, (width * at).toInt(), 0, 1, height)
        }
    }

    private fun black(
        pixels: ByteBuffer,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
    ): Boolean {
        pixels.clear()
        GLES30.glReadPixels(x, y, width, height, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, pixels)
        for (pixel in 0 until width * height) {
            val base = pixel * BYTES_PER_PIXEL
            // red, green and blue; alpha says nothing about what shows
            for (channel in 0 until 3) {
                if ((pixels.get(base + channel).toInt() and 0xFF) > DARKEST_SEEN) return false
            }
        }
        return true
    }

    private companion object {
        const val BYTES_PER_PIXEL = 4
        val FRACTIONS = floatArrayOf(0.25f, 0.5f, 0.75f)

        /** A channel this bright or less still counts as black: a dark preset is not empty, a near-black one is. */
        const val DARKEST_SEEN = 6
    }
}
