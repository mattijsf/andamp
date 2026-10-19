// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.visualizer.projectm

/**
 * The JNI declarations for libprojectM's C API. [ProjectMEngine] holds the
 * rules: which handle is alive, which thread may call, what a null means.
 * Every function here is unchecked: a dead handle crashes the process.
 */
internal object ProjectMNative {
    external fun nativeMaxSamples(): Int

    external fun nativeCreate(
        width: Int,
        height: Int,
        meshWidth: Int,
        meshHeight: Int,
        fps: Int,
    ): Long

    external fun nativeDestroy(handle: Long)

    external fun nativeSetWindowSize(
        handle: Long,
        width: Int,
        height: Int,
    )

    external fun nativeSetMeshSize(
        handle: Long,
        width: Int,
        height: Int,
    )

    external fun nativeSetFps(
        handle: Long,
        fps: Int,
    )

    external fun nativeRenderFrame(handle: Long)

    external fun nativeAddPcmFloat(
        handle: Long,
        samples: FloatArray,
        count: Int,
        channels: Int,
    )

    external fun nativeLoadPresetFile(
        handle: Long,
        path: String,
        smooth: Boolean,
    )

    external fun nativeSetTextureSearchPaths(
        handle: Long,
        paths: Array<String>,
    )

    external fun nativePlaylistCreate(handle: Long): Long

    external fun nativePlaylistDestroy(playlist: Long)

    external fun nativePlaylistClear(playlist: Long)

    external fun nativePlaylistAddPath(
        playlist: Long,
        path: String,
        recurse: Boolean,
    ): Int

    /** Adds every one of [paths], in order, without checking for one already there; answers how many it took. */
    external fun nativePlaylistAddPresets(
        playlist: Long,
        paths: Array<String>,
    ): Int

    /**
     * Starts collecting the presets the playlist could not switch to; answers the collector.
     * It also takes over the playlist's retry, moving past up to [retries] broken presets per
     * switch, so every one it skips is collected.
     */
    external fun nativePlaylistWatchFailures(
        playlist: Long,
        retries: Int,
    ): Long

    /** The way the next switch goes, for the collector's retry: back for "previous", on for the rest. */
    external fun nativeWatchDirection(
        watch: Long,
        backwards: Boolean,
    )

    external fun nativePlaylistUnwatchFailures(
        playlist: Long,
        watch: Long,
    )

    /**
     * What [nativePlaylistWatchFailures] collected since the last call: file, reason, file,
     * reason..., as [zeroEnded] strings.
     */
    external fun nativeTakeFailures(watch: Long): ByteArray

    /** The lines projectM wrote to its error stream since the last call, from every engine, as [zeroEnded] strings. */
    external fun nativeTakeLog(): ByteArray

    /** UTF-8 strings that each end with a zero byte, as the take functions hand them over. */
    fun zeroEnded(bytes: ByteArray): List<String> = String(bytes, Charsets.UTF_8).split('\u0000').dropLast(1)

    external fun nativePlaylistSize(playlist: Long): Int

    external fun nativePlaylistSetShuffle(
        playlist: Long,
        shuffle: Boolean,
    )

    external fun nativePlaylistSetRetryCount(
        playlist: Long,
        retries: Int,
    )

    external fun nativePlaylistPlayNext(
        playlist: Long,
        hardCut: Boolean,
    ): Int

    external fun nativePlaylistPlayPrevious(
        playlist: Long,
        hardCut: Boolean,
    ): Int

    external fun nativePlaylistSetPosition(
        playlist: Long,
        position: Int,
        hardCut: Boolean,
    ): Int

    external fun nativePlaylistPosition(playlist: Long): Int

    external fun nativePlaylistItem(
        playlist: Long,
        index: Int,
    ): String?
}
