// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.visualizer.projectm

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.GLES30
import android.util.Log
import android.util.Size
import nl.mattix.andamp.core.player.FramePacer
import nl.mattix.andamp.core.player.PcmSource
import nl.mattix.andamp.visualizer.core.VisualizerView

/**
 * The projectM rendering surface: the shared [VisualizerView] with an EGL
 * context driven by hand on its render thread. [EglSurface] says why it is not
 * a `GLSurfaceView`.
 */
@SuppressLint("ViewConstructor")
class ProjectMView(
    context: Context,
) : VisualizerView<ProjectMEngine>(context) {
    /**
     * Builds the audio source from the frame rate and from how much PCM
     * projectM looks at in one frame. The rate is known only after the engine
     * exists, and the source sizes its buffer from both.
     */
    @Volatile
    var pcmSourceFactory: ((fps: Int, maxSamples: Int) -> PcmSource)? = null

    /**
     * The presets to run. A property and not a queued command, because a surface
     * can be destroyed and rebuilt (a fold, going to the background) and the
     * engine that comes back has to load the same pack again.
     *
     * Null keeps projectM's built-in idle preset.
     */
    @Volatile
    var presets: PresetPack? = null

    /** A property for the same reason [presets] is. */
    @Volatile
    var shuffle: Boolean = false

    /** Winamp's spacebar, and this window's tap. */
    override fun nextPreset() = onRenderThread { engine -> if (engine.playlistSize > 0) engine.playlistNext() }

    override fun previousPreset() = onRenderThread { engine -> if (engine.playlistSize > 0) engine.playlistPrevious() }

    /** Plays the preset at [index] of [onPlaylistChanged]'s list. */
    override fun goToPreset(index: Int) = onRenderThread { engine -> engine.playlistGoTo(index) }

    override val threadName = "projectM"

    // a render thread that dies with an exception must not take the app with it
    @Suppress("TooGenericExceptionCaught")
    override fun renderLoop(
        texture: SurfaceTexture,
        width: Int,
        height: Int,
    ) {
        var egl: EglSurface? = null
        val engine = ProjectMEngine()
        try {
            egl = EglSurface(texture)
            egl.makeCurrent()
            GLES30.glViewport(0, 0, width, height)
            var appliedSize = Size(width, height)
            val fps = targetFps()
            engine.create(width, height, MESH_W, MESH_H, fps)
            val maxSamples = ProjectMEngine.ANALYSIS_SAMPLES
            val pcm = pcmSourceFactory?.invoke(fps, maxSamples)
            val pacer = FramePacer(fps)
            val presetLoader =
                PresetLoader(
                    engine,
                    onPresetChanged = { name ->
                        stickyPreset = name
                        announcePreset(name)
                    },
                    onPlaylistChanged = ::announcePlaylist,
                    restoreTo = { stickyPreset },
                )
            while (stillCurrent()) {
                requestedSize?.takeIf { it != appliedSize }?.let { size ->
                    appliedSize = size
                    GLES30.glViewport(0, 0, size.width, size.height)
                    engine.resize(size.width, size.height)
                }
                presetLoader.sync(presets, shuffle)
                drainCommands(engine)
                presetLoader.reportSwitch()
                val samples = pcm?.read()
                if (samples != null) engine.addPcmFloat(samples, samples.size.coerceAtMost(maxSamples))
                engine.renderFrame()
                egl.makeOpaque()
                if (!egl.swapBuffers()) break
                pacer.await()
            }
        } catch (e: Exception) {
            Log.w(TAG, "projectM render loop stopped", e)
        } finally {
            engine.destroy()
            egl?.release()
        }
    }

    private companion object {
        const val TAG = "ProjectMView"

        /**
         * The warp mesh, and so the frame's cost: MilkDrop's "per-pixel"
         * equations run once per mesh vertex.
         */
        const val MESH_W = 48
        const val MESH_H = 36
    }
}
