// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.visualizer.projectm

/**
 * A directory of `.milk` presets, and the textures they name.
 *
 * projectM loads presets by filesystem path, so these are paths to files on
 * disk and never content URIs.
 */
data class PresetPack(
    /** Directory scanned for presets, recursively. */
    val presetsDir: String,
    /**
     * Where MilkDrop 2 presets look up the texture files they name. Without
     * these a preset that samples a texture renders wrong and reports no error.
     */
    val textureDirs: List<String> = emptyList(),
)
