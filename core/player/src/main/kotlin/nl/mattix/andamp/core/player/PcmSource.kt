// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.core.player

/**
 * Where a visualizer view gets its audio, one frame's worth per rendered frame. Both
 * visualizer engines read PCM through this contract.
 */
fun interface PcmSource {
    /**
     * The samples to feed this frame: mono, -1..1, oldest first, or null when there is no
     * audio. Called once per frame on the render thread, so the returned array must be reused.
     */
    fun read(): FloatArray?
}
