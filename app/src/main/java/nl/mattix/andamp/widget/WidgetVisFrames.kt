// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.widget

/**
 * The short loop of visualizer frames the launcher is currently cycling. Held in memory: frames
 * exist only while the player's process is alive and playing. A widget drawn without them shows the
 * dark analyzer.
 */
object WidgetVisFrames {
    /** How many pictures the launcher cycles; the layout declares this many slots. */
    const val COUNT = 8

    /**
     * A batch and the generation that names it, published as one object so a reader cannot pair old
     * frames with a new generation.
     */
    data class Batch(
        val frames: List<VisFrame>,
        val generation: Int,
    )

    @Volatile
    var batch: Batch = Batch(emptyList(), 0)
        private set

    val frames: List<VisFrame> get() = batch.frames

    fun offer(captured: List<VisFrame>) {
        batch = Batch(captured, batch.generation + 1)
    }

    fun clear() {
        val now = batch
        if (now.frames.isEmpty()) return
        batch = Batch(emptyList(), now.generation + 1)
    }
}
