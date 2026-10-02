// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.visualizer.avs

/**
 * AVS's global buffers: somewhere to put a frame and get it back later.
 *
 * Buffer Save writes and reads them, and an Effect List can take its input from
 * one or send its output to one. They are global to the preset: a list deep in
 * the tree can hand a frame to a list elsewhere in it.
 *
 * Each buffer is allocated on first use.
 */
class AvsBuffers(
    private val width: Int,
    private val height: Int,
    count: Int = COUNT,
) {
    private val frames = arrayOfNulls<AvsFrame>(count)

    val size get() = frames.size

    /** The buffer at [index], made if this is its first use. Null for an index AVS has no buffer for. */
    operator fun get(index: Int): AvsFrame? {
        if (index !in frames.indices) return null
        return frames[index] ?: AvsFrame(width, height).also { frames[index] = it }
    }

    /** The buffer at [index] if it has been made; never creates one. */
    fun peek(index: Int): AvsFrame? = frames.getOrNull(index)

    /** How many have been made. */
    fun allocated() = frames.count { it != null }

    /** The bank for another frame size, with every buffer discarded; this bank when the size is the same. */
    fun resizedTo(
        width: Int,
        height: Int,
    ) = if (width == this.width && height == this.height) this else AvsBuffers(width, height, frames.size)

    private companion object {
        /** AVS has eight; a preset file numbers them from 1. */
        const val COUNT = 8
    }
}
