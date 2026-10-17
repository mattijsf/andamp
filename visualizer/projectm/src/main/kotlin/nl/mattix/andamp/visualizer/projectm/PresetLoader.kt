// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.visualizer.projectm

import nl.mattix.andamp.visualizer.core.PresetGaps
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
    /**
     * A preset's file and what of it is not showing: the parts projectM left out once it runs,
     * all of it when it failed.
     */
    private val onPresetGaps: (String, PresetGaps) -> Unit = { _, _ -> },
    /** Presets to switch past, unless picked by their place; see [ProjectMEngine.picked]. */
    private val skip: () -> Set<String> = { emptySet() },
    /** Milliseconds, for [BlankWatch]. */
    private val clock: () -> Long = { System.nanoTime() / NANOS_PER_MILLI },
) {
    private var loaded: PresetPack? = null
    private var shuffling: Boolean? = null
    private var reportedPosition = -1
    private var reportedPath: String? = null
    private val log = PresetLog()
    private val blankWatch = BlankWatch()
    private var skipping = 0

    // the presets found drawing only black, this session; one that draws again leaves the set
    private val blank = mutableSetOf<String>()

    fun sync(
        pack: PresetPack?,
        shuffle: Boolean,
    ) {
        if (pack != loaded) {
            loaded = pack
            reportedPosition = -1
            if (pack != null) {
                engine.setTextureSearchPaths(pack.textureDirs)
                if (pack.files.isNotEmpty()) engine.loadPresetFiles(pack.files) else engine.loadPresetDirectory(pack.presetsDir)
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
        // a preset known not to show is passed by, the way the listener was going, but at most
        // once round the list
        if (!engine.picked && skipping < engine.playlistSize && path in skip()) {
            skipping++
            if (engine.backwards) engine.playlistPrevious() else engine.playlistNext()
            return
        }
        skipping = 0
        engine.switched()
        reportedPath = path
        blankWatch.start(path, clock())
        onPresetChanged(File(path).nameWithoutExtension)
        onPresetGaps(path, gapsOf(path))
    }

    /**
     * Reads what projectM wrote since the last call. Called before [reportSwitch], so a preset
     * is announced with what it leaves out; a part that goes missing later, such as a texture
     * looked up while drawing, is announced again for the preset showing.
     */
    fun readLog() {
        val grew = log.read(engine.takeLog())
        val showing = reportedPath ?: return
        if (showing in grew) onPresetGaps(showing, gapsOf(showing))
    }

    /** Whether the frame about to be drawn should be checked for drawing nothing. */
    fun wantsBlankCheck(): Boolean = blankWatch.wants(clock())

    /** What a check of the frame found; see [BlankWatch]. */
    fun blankChecked(
        black: Boolean,
        audible: Boolean,
    ) {
        val path = blankWatch.watching ?: return
        val draws = blankWatch.sampled(black, audible, clock()) ?: return
        val changed = if (draws) blank.remove(path) else blank.add(path)
        if (changed) onPresetGaps(path, gapsOf(path))
    }

    private fun gapsOf(path: String) = PresetGaps(missing = log.leftOut(path), blank = path in blank)

    /** Names every preset projectM tried and could not show since the last call, with its reason. */
    fun reportFailures() {
        if (loaded == null) return
        engine.takeFailures().forEach { (path, reason) ->
            onPresetGaps(
                path,
                PresetGaps(
                    unreadable = true,
                    reason =
                        reason.trim().takeUnless {
                            it.isEmpty() ||
                                it in SAYS_NOTHING
                        },
                ),
            )
        }
    }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L

        /** Reasons projectM passes on from a C++ exception that carried no message. */
        val SAYS_NOTHING = setOf("std::exception", "Unknown error")
    }
}
