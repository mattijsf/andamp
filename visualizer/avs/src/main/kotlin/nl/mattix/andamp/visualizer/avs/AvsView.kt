// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.visualizer.avs

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.util.Log
import android.util.Size
import android.view.Surface
import nl.mattix.andamp.core.player.FramePacer
import nl.mattix.andamp.core.player.PcmSource
import nl.mattix.andamp.visualizer.core.PresetGaps
import nl.mattix.andamp.visualizer.core.VisualizerView
import java.io.File

/**
 * The AVS rendering surface: a [VisualizerView] whose frames are painted by the
 * CPU on its render thread.
 *
 * The engine fills an `IntArray`, which is copied into a [Bitmap] and drawn
 * onto the surface's canvas, scaled up without filtering. The engine renders at
 * most [MAX_WIDTH] wide.
 */
@SuppressLint("ViewConstructor")
class AvsView(
    context: Context,
) : VisualizerView<AvsPlaylist>(context) {
    /** Builds the audio source once the render thread knows the frame rate. */
    @Volatile
    var pcmSourceFactory: ((fps: Int) -> PcmSource)? = null

    /**
     * The directory of `.avs` files to play. A property and not a queued
     * command, because a surface can be destroyed and rebuilt and the engine
     * that comes back has to load the same pack again. Null plays
     * [AvsIdlePreset].
     */
    @Volatile
    var presetDirectory: File? = null

    /**
     * The `.avs` files to play instead of [presetDirectory]'s, in this order: presets from
     * several packs, such as the listener's favorites. Null plays the directory.
     */
    @Volatile
    var presetFiles: List<String>? = null

    /**
     * The packs [presetFiles] come from. A preset's pictures are looked up in the pack it is in,
     * which is whichever of these holds its file.
     */
    @Volatile
    var presetRoots: List<File> = emptyList()

    /** A property for the same reason [presetDirectory] is. */
    @Volatile
    var shuffle: Boolean = false

    /** Winamp's spacebar, and this window's tap. */
    override fun nextPreset() = onRenderThread { it.next() }

    override fun previousPreset() = onRenderThread { it.previous() }

    /** Plays the preset at [index] of [onPlaylistChanged]'s list. */
    override fun goToPreset(index: Int) = onRenderThread { it.goTo(index) }

    override val threadName = "avs"

    @Suppress("TooGenericExceptionCaught") // a render crash must not take the app down; it is logged instead
    override fun renderLoop(
        texture: SurfaceTexture,
        width: Int,
        height: Int,
    ) {
        val surface = Surface(texture)
        val engine = AvsEngine(MAX_WIDTH, MAX_WIDTH * 3 / 4)
        val images = PackImages()
        engine.images = images
        engine.text = AndroidTextPainter()
        try {
            val fps = targetFps()
            val pacer = FramePacer(fps)
            val pcm = pcmSourceFactory?.invoke(fps)
            val audio = AvsAudio()
            val beat = AvsBeat()
            val playlist = AvsPlaylist()
            val playlistSync = PlaylistSync(playlist, ::announcePlaylist)
            val painter = Painter()
            var announced: String? = null
            var loadedPath: String? = null
            // the idle preset is loaded first: an empty playlist's current is
            // null, the same as the initial loadedPath, so the loop below
            // would not load it
            announceGaps(null, load(engine, null))

            while (stillCurrent()) {
                sync(playlistSync, playlist)
                // a rebuilt loop goes back to what was playing before it
                val restore = stickyPreset
                if (loadedPath == null && restore != null) playlist.goTo(restore)
                if (playlist.current != loadedPath) {
                    loadedPath = playlist.current
                    // a preset's pictures are looked up in the pack it came from
                    images.directory = packOf(loadedPath)
                    announceGaps(loadedPath, load(engine, loadedPath))
                    stickyPreset = loadedPath
                }
                announced = announce(loadedPath, announced)

                val frame = audioFrame(pcm, audio, beat)
                sizeFor(requestedSize)?.let { engine.resize(it.width, it.height) }
                val painted =
                    try {
                        painter.paint(surface, engine.render(frame))
                    } catch (e: RuntimeException) {
                        // a preset that throws while rendering is replaced by
                        // the idle preset, and the loop goes on. A rebuilt loop
                        // is not sent back to it.
                        Log.e(TAG, "preset $loadedPath crashed; falling back to idle", e)
                        load(engine, null)
                        stickyPreset = null
                        announceGaps(loadedPath, PresetGaps(unreadable = true, reason = e.message))
                        true
                    }
                if (!painted) break
                pacer.await()
            }
        } catch (e: Exception) {
            Log.e(TAG, "AVS render loop died", e)
        } finally {
            engine.close()
            surface.release()
        }
    }

    /** Tells the UI the preset's name once per switch; returns what has been told. */
    private fun announce(
        path: String?,
        told: String?,
    ): String {
        val name = path?.let { File(it).nameWithoutExtension } ?: IDLE_NAME
        if (name != told) announcePreset(name)
        return name
    }

    /** Applies directory and shuffle changes through [PlaylistSync], then whatever the UI queued. */
    private fun sync(
        sync: PlaylistSync,
        playlist: AvsPlaylist,
    ) {
        sync.apply(presetDirectory, presetFiles, shuffle)
        drainCommands(playlist)
    }

    /** The pack [path] belongs to, where its pictures are: one of [presetRoots], or the directory playing. */
    private fun packOf(path: String?): File? =
        path?.let { p -> presetRoots.firstOrNull { p.startsWith(it.absolutePath + File.separator) } } ?: presetDirectory

    /**
     * Loads the preset at [path]; the idle preset when [path] is null, unreadable or does not parse.
     * Answers what of the preset is not showing.
     */
    private fun load(
        engine: AvsEngine,
        path: String?,
    ): PresetGaps = AvsPresetCheck.load(engine, path)

    private fun audioFrame(
        pcm: PcmSource?,
        audio: AvsAudio,
        beat: AvsBeat,
    ): AvsAudioFrame {
        val samples = pcm?.read()
        return if (samples == null) {
            beat.idle()
            AvsAudioFrame()
        } else {
            audio.feed(samples)
            audio.capture()
            // the beat comes from the same window the components will see,
            // through AVS's own detector
            val frame = audio.frame(beat = false)
            audio.frame(beat.update(frame.waveform))
        }
    }

    /** The engine's resolution: capped at [MAX_WIDTH] wide, keeping the surface's shape. */
    private fun sizeFor(surface: Size?): Size? {
        if (surface == null || surface.width <= 0 || surface.height <= 0) return null
        val width = surface.width.coerceAtMost(MAX_WIDTH)
        val height = (surface.height.toLong() * width / surface.width).toInt().coerceAtLeast(1)
        return Size(width, height)
    }

    /** The engine's IntArray onto the surface, scaled without filtering. */
    private class Painter {
        private var bitmap: Bitmap? = null
        private val paint = Paint() // no FILTER_BITMAP_FLAG: the upscale is unfiltered
        private val into = Rect()

        fun paint(
            surface: Surface,
            frame: AvsFrame,
        ): Boolean {
            val target = bitmapFor(frame)
            target.setPixels(frame.pixels, 0, frame.width, 0, 0, frame.width, frame.height)
            val canvas = lockOrNull(surface) ?: return false
            try {
                into.set(0, 0, canvas.width, canvas.height)
                canvas.drawBitmap(target, null, into, paint)
            } finally {
                surface.unlockCanvasAndPost(canvas)
            }
            return true
        }

        /** Null when the surface went away mid-frame, which lockCanvas reports with any of these three. */
        private fun lockOrNull(surface: Surface) =
            try {
                surface.lockCanvas(null)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "surface gone: ${e.message}")
                null
            } catch (e: IllegalStateException) {
                Log.w(TAG, "surface gone: ${e.message}")
                null
            } catch (e: Surface.OutOfResourcesException) {
                Log.w(TAG, "surface gone: ${e.message}")
                null
            }

        private fun bitmapFor(frame: AvsFrame): Bitmap {
            val existing = bitmap
            if (existing != null && existing.width == frame.width && existing.height == frame.height) return existing
            existing?.recycle()
            return Bitmap.createBitmap(frame.width, frame.height, Bitmap.Config.ARGB_8888).also { bitmap = it }
        }
    }

    companion object {
        private const val TAG = "AvsView"

        /** The most PCM a frame's analysis uses; equal to [AvsAudio.RING]. */
        const val MAX_PCM_SAMPLES = 2048

        /**
         * The engine renders small and the view scales up. Presets are tuned
         * for a small frame: a 1-pixel line on a wider frame spreads the same
         * additive light over more pixels and looks dimmer.
         */
        private const val MAX_WIDTH = 196

        private const val IDLE_NAME = "AVS"
    }
}
