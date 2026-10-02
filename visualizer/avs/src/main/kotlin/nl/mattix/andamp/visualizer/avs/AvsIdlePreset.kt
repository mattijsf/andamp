// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.visualizer.avs

/**
 * The preset that plays when nothing is imported. It is this app's own, because
 * other people's presets carry their authors' terms.
 *
 * The component bodies are written here byte for byte. None is scripted, so the
 * preset also runs in JVM tests, where the native evaluator is not loaded.
 */
object AvsIdlePreset {
    fun preset(): AvsPreset =
        AvsPreset(
            clearEveryFrame = false,
            components =
                listOf(
                    // the last frame pulled slowly toward the center: the trail
                    builtin(BLITTER_FEEDBACK, int32(ZOOM_DRIFT) + int32(ZOOM_KICK) + int32(1) + int32(1)),
                    // and faded toward black so the trail ends
                    builtin(FADE_OUT, int32(FADE_SPEED) + int32(0)),
                    // the waveform, centered, cycling between two colors
                    builtin(
                        SIMPLE,
                        int32(LINE_SCOPE_CENTRE) + int32(2) + int32(GREEN) + int32(CYAN),
                    ),
                    // a particle circling with the bass, additive over the lot
                    builtin(
                        MOVING_PARTICLE,
                        int32(PARTICLE_FLAGS) + int32(WHITE) + int32(PARTICLE_DISTANCE) +
                            int32(PARTICLE_SIZE) + int32(PARTICLE_BEAT_SIZE) + int32(1),
                    ),
                ),
        )

    private fun builtin(
        id: Int,
        body: ByteArray,
    ): AvsComponent.Builtin = AvsComponent.Builtin(id, checkNotNull(AvsComponents[id]) { "no component with id $id" }, body)

    private fun int32(value: Int) =
        byteArrayOf(
            value.toByte(),
            (value shr 8).toByte(),
            (value shr 16).toByte(),
            (value shr 24).toByte(),
        )

    private const val SIMPLE = 0x00
    private const val FADE_OUT = 0x03
    private const val BLITTER_FEEDBACK = 0x04
    private const val MOVING_PARTICLE = 0x08

    /** Line scope (low bits 2), center position (bits 4-5 = 2). */
    private const val LINE_SCOPE_CENTRE = 0x22

    /** Just past 0x20 (a zoom of one): a slow pull toward the center, harder on a beat. */
    private const val ZOOM_DRIFT = 0x2C
    private const val ZOOM_KICK = 0x48
    private const val FADE_SPEED = 6

    /** Enabled and grows on a beat. */
    private const val PARTICLE_FLAGS = 0x03
    private const val PARTICLE_DISTANCE = 12
    private const val PARTICLE_SIZE = 6
    private const val PARTICLE_BEAT_SIZE = 16

    private const val GREEN = 0x00FF66
    private const val CYAN = 0x00CCFF
    private const val WHITE = 0xFFFFFF
}
