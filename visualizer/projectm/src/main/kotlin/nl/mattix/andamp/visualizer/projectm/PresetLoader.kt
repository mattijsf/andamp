// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.visualizer.projectm

import java.io.File

/**
 * Keeps the engine's playlist in step with what the view was told to show, and
 * reports which preset is running.
 *
 * Lives on the render thread. Loading a pack rescans a directory and reloads
 * every texture, so it happens only when the pack changed: [sync] runs every
 * frame and does nothing until its arguments differ from the last call's.
 */
internal class PresetLoader(
    private val engine: ProjectMEngine,
    private val onPresetChanged: (String) -> Unit,
    private val onPlaylistChanged: (List<String>) -> Unit,
    /** What was playing before this loop existed; see `VisualizerView.stickyPreset`. */
    private val restoreTo: () -> String? = { null },
) {
    private var loaded: PresetPack? = null
    private var shuffling: Boolean? = null
    private var reportedPosition = -1

    fun sync(
        pack: PresetPack?,
        shuffle: Boolean,
    ) {
        if (pack != loaded) {
            loaded = pack
            reportedPosition = -1
            if (pack != null) {
                engine.setTextureSearchPaths(pack.textureDirs)
                engine.loadPresetDirectory(pack.presetsDir)
                // published once per pack: this can be thousands of strings
                // crossing to the main thread
                val paths = engine.playlistPaths()
                onPlaylistChanged(paths)
                // a rebuilt loop goes back to what was playing before it;
                // otherwise the pack restarts at its first preset
                PresetRestore.indexOf(paths, restoreTo()).takeIf { it >= 0 }?.let { engine.playlistGoTo(it) }
            } else {
                onPlaylistChanged(emptyList())
            }
            // a null pack leaves whatever is playing: projectM has no "unload",
            // and the idle preset returns on its own when the engine is rebuilt
        }
        if (shuffle != shuffling) {
            shuffling = shuffle
            if (loaded != null) engine.playlistSetShuffle(shuffle)
        }
    }

    /** Names the preset that is now showing, once per change. */
    fun reportSwitch() {
        if (loaded == null || engine.playlistSize == 0) return
        val position = engine.playlistPosition
        if (position == reportedPosition) return
        reportedPosition = position
        val path = engine.playlistItem(position) ?: return
        onPresetChanged(File(path).nameWithoutExtension)
    }
}
