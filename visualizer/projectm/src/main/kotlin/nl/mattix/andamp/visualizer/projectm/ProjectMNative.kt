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
