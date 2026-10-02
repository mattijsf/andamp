// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.visualizer.projectm

/**
 * The Kotlin face of libprojectM. One instance owns one native handle.
 *
 * Every method must be called on the thread holding the GL context: libprojectM
 * is not thread-safe and renders with whatever context is current.
 * [ProjectMView]'s render thread is that thread, and work arriving from the UI
 * is queued onto it.
 */
class ProjectMEngine {
    private var handle = 0L
    private var playlist = 0L

    /** Whether [create] succeeded and [destroy] has not run. */
    val isAlive: Boolean get() = handle != 0L

    /** Most samples one [addPcmFloat] can carry; anything beyond is dropped by projectM. */
    val maxSamples: Int get() = ProjectMNative.nativeMaxSamples()

    /**
     * @param meshWidth the warp mesh's width. MilkDrop's per-pixel equations run
     *   per mesh vertex, so the mesh size sets the frame's cost.
     */
    fun create(
        width: Int,
        height: Int,
        meshWidth: Int,
        meshHeight: Int,
        fps: Int,
    ) {
        check(!isAlive) { "already created" }
        handle = ProjectMNative.nativeCreate(width, height, meshWidth, meshHeight, fps)
        check(isAlive) { "projectm_create failed" }
    }

    fun resize(
        width: Int,
        height: Int,
    ) = ProjectMNative.nativeSetWindowSize(alive(), width, height)

    fun setMeshSize(
        width: Int,
        height: Int,
    ) = ProjectMNative.nativeSetMeshSize(alive(), width, height)

    fun setFps(fps: Int) = ProjectMNative.nativeSetFps(alive(), fps)

    /**
     * Hands projectM raw PCM; it runs its own analysis, so no FFT is wanted here.
     * [samples] is expected to be a reused buffer, since this is called every frame.
     */
    fun addPcmFloat(
        samples: FloatArray,
        count: Int = samples.size,
        stereo: Boolean = false,
    ) = ProjectMNative.nativeAddPcmFloat(alive(), samples, count.coerceAtMost(samples.size), if (stereo) STEREO else MONO)

    /** Renders one frame into the currently bound framebuffer. */
    fun renderFrame() = ProjectMNative.nativeRenderFrame(alive())

    fun loadPresetFile(
        path: String,
        smooth: Boolean,
    ) = ProjectMNative.nativeLoadPresetFile(alive(), path, smooth)

    /**
     * MilkDrop 2 presets name texture files and render wrong (or not at all)
     * without them, so a pack's `textures/` directory has to be registered
     * alongside its presets. Reloads every texture, so it is not a per-frame call.
     */
    fun setTextureSearchPaths(paths: List<String>) = ProjectMNative.nativeSetTextureSearchPaths(alive(), paths.toTypedArray())

    /**
     * Replaces the playlist with everything under [directory].
     *
     * @return how many presets were found.
     */
    fun loadPresetDirectory(
        directory: String,
        recurse: Boolean = true,
    ): Int {
        val handle = playlistHandle()
        ProjectMNative.nativePlaylistClear(handle)
        val added = ProjectMNative.nativePlaylistAddPath(handle, directory, recurse)
        if (added > 0) ProjectMNative.nativePlaylistSetPosition(handle, 0, true)
        return added
    }

    val playlistSize: Int get() = ProjectMNative.nativePlaylistSize(playlistHandle())

    fun playlistNext(hardCut: Boolean = true) = ProjectMNative.nativePlaylistPlayNext(playlistHandle(), hardCut)

    fun playlistPrevious(hardCut: Boolean = true) = ProjectMNative.nativePlaylistPlayPrevious(playlistHandle(), hardCut)

    fun playlistSetShuffle(shuffle: Boolean) = ProjectMNative.nativePlaylistSetShuffle(playlistHandle(), shuffle)

    /**
     * Jumps to a preset by its place in the playlist. An index outside the
     * playlist is refused: projectm_playlist_set_position turns it into 0, so a
     * stale index from a list that has since been reloaded would restart the pack.
     *
     * @return whether it moved.
     */
    fun playlistGoTo(
        index: Int,
        hardCut: Boolean = true,
    ): Boolean {
        val handle = playlistHandle()
        if (index < 0 || index >= ProjectMNative.nativePlaylistSize(handle)) return false
        ProjectMNative.nativePlaylistSetPosition(handle, index, hardCut)
        return true
    }

    /**
     * Every preset's file path, in playlist order.
     *
     * The order is projectM's own, from walking the pack directory, and it is
     * the order [playlistGoTo] indexes into, so a list shown to the listener has
     * to come from here.
     */
    fun playlistPaths(): List<String> {
        val handle = playlistHandle()
        val size = ProjectMNative.nativePlaylistSize(handle)
        return (0 until size).mapNotNull { ProjectMNative.nativePlaylistItem(handle, it) }
    }

    /** Index of the preset currently showing. */
    val playlistPosition: Int get() = ProjectMNative.nativePlaylistPosition(playlistHandle())

    /** The preset file at [index], or null when the playlist is shorter than that. */
    fun playlistItem(index: Int): String? = ProjectMNative.nativePlaylistItem(playlistHandle(), index)

    /**
     * The playlist is created lazily: an engine that never loads a pack keeps
     * rendering projectM's built-in idle preset.
     */
    private fun playlistHandle(): Long {
        if (playlist == 0L) {
            playlist = ProjectMNative.nativePlaylistCreate(alive())
            check(playlist != 0L) { "projectm_playlist_create failed" }
            // projectM's HLSL translation does not handle every preset in the
            // wild. Retrying moves on to the next one instead of leaving the
            // window on a preset that failed to compile.
            ProjectMNative.nativePlaylistSetRetryCount(playlist, PRESET_RETRIES)
        }
        return playlist
    }

    fun destroy() {
        if (!isAlive) return
        // the playlist holds a reference to the projectM instance, so it goes first
        if (playlist != 0L) {
            ProjectMNative.nativePlaylistDestroy(playlist)
            playlist = 0L
        }
        ProjectMNative.nativeDestroy(handle)
        handle = 0L
    }

    private fun alive(): Long {
        check(isAlive) { "engine is not created" }
        return handle
    }

    init {
        ProjectM.ensureLoaded()
    }

    private companion object {
        // projectm_channels in types.h
        const val MONO = 1
        const val STEREO = 2

        /** How many presets the playlist may skip past before giving up on a switch. */
        const val PRESET_RETRIES = 5
    }
}
