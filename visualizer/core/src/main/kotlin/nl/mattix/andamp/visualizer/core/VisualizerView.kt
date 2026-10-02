// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.visualizer.core

import android.content.Context
import android.graphics.SurfaceTexture
import android.util.Size
import android.view.TextureView
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * The window every visualizer engine renders through.
 *
 * A [TextureView], because a `SurfaceView` punches a hole through the app's translucent
 * window, with one dedicated thread owning the surface. An engine module supplies
 * [renderLoop]; this class owns what surrounds it:
 *
 * - The lifecycle gate ([rendering]): a TextureView keeps its surface while the activity
 *   is stopped, so the owner drives this from the lifecycle. False stops the thread and
 *   leaves the surface alone; true starts a new thread.
 * - Resize without restart ([requestedSize]): the loop applies a new size as a new
 *   viewport and keeps its engine.
 * - The command queue: menus run on the main thread, engines on the render thread; calls
 *   queue with [onRenderThread] and the loop drains them with [drainCommands].
 * - The zombie guard ([stillCurrent]): when a [stop] times out on its join, the old loop
 *   must still end after a restart sets `running` true again.
 *
 * @param T what a queued command runs against on the render thread.
 */
abstract class VisualizerView<T>(
    context: Context,
) : TextureView(context),
    TextureView.SurfaceTextureListener {
    /** Told the name of each preset as it starts, on the main thread. */
    @Volatile
    var onPresetChanged: ((String) -> Unit)? = null

    /**
     * The loaded presets, in playlist order, told once per pack. The index into this list
     * is what [goToPreset] takes.
     */
    @Volatile
    var onPlaylistChanged: ((List<String>) -> Unit)? = null

    /** Winamp's spacebar, and the plug-in window's tap. */
    abstract fun nextPreset()

    abstract fun previousPreset()

    /** Plays the preset at [index] of [onPlaylistChanged]'s list. */
    abstract fun goToPreset(index: Int)

    private val commands = ConcurrentLinkedQueue<(T) -> Unit>()

    /** Queues [action] for the render thread; every engine call belongs to it. */
    protected fun onRenderThread(action: (T) -> Unit) {
        commands += action
    }

    /** Runs what the UI queued, on the loop's own thread, against [target]. */
    protected fun drainCommands(target: T) {
        while (true) {
            val command = commands.poll() ?: return
            command(target)
        }
    }

    /** Whether frames are wanted at all; the owner drives this from the lifecycle. */
    var rendering: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            if (value) surfaceTexture?.let { start(it, width, height) } else stop()
        }

    /** Read by the render loop; a resize is applied on the thread that owns the surface. */
    @Volatile
    protected var requestedSize: Size? = null
        private set

    @Volatile
    private var running = false

    @Volatile
    private var thread: Thread? = null

    /** What the loop's thread is called in a trace. */
    protected abstract val threadName: String

    /**
     * The loop itself: everything from taking the surface to releasing what it
     * made. Runs on its own thread; loop while [stillCurrent] and drain the
     * queue each frame.
     */
    protected abstract fun renderLoop(
        texture: SurfaceTexture,
        width: Int,
        height: Int,
    )

    /** The loop's condition: false when stopped or when a newer thread took over. */
    protected fun stillCurrent() = running && thread === Thread.currentThread()

    /** Hops a preset name to the main thread: listeners drive Compose state. */
    protected fun announcePreset(name: String) {
        post { onPresetChanged?.invoke(name) }
    }

    /**
     * The preset that was playing, so a rebuilt render loop can go back to it.
     *
     * The surface is destroyed and rebuilt on every trip through the background, and an
     * engine that starts its pack from the top each time changes preset without the
     * listener asking. A render loop writes this on every switch and steers back to it
     * before its first frame.
     *
     * The value is whatever that engine addresses presets by (AVS a path, projectM a
     * name); only the engine reads it back.
     */
    @Volatile
    var stickyPreset: String? = null
        protected set

    /** Hops the playlist to the main thread, for the same reason. */
    protected fun announcePlaylist(names: List<String>) {
        post { onPlaylistChanged?.invoke(names) }
    }

    /** Match the panel: presets move per frame, so the frame rate is the speed. */
    protected fun targetFps(): Int = display?.refreshRate?.toInt()?.coerceIn(MIN_FPS, MAX_FPS) ?: DEFAULT_FPS

    init {
        isOpaque = true
        surfaceTextureListener = this
    }

    final override fun onSurfaceTextureAvailable(
        surface: SurfaceTexture,
        width: Int,
        height: Int,
    ) = start(surface, width, height)

    final override fun onSurfaceTextureSizeChanged(
        surface: SurfaceTexture,
        width: Int,
        height: Int,
    ) {
        requestedSize = Size(width, height)
    }

    final override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        stop()
        return true
    }

    final override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit

    private fun start(
        texture: SurfaceTexture,
        width: Int,
        height: Int,
    ) {
        if (running || !rendering) return
        if (width <= 0 || height <= 0) return
        running = true
        requestedSize = Size(width, height)
        thread =
            Thread({ renderLoop(texture, width, height) }, threadName).also {
                it.priority = Thread.NORM_PRIORITY + 1 // visuals shouldn't lose to background work
                it.start()
            }
    }

    private fun stop() {
        running = false
        thread?.join(JOIN_TIMEOUT_MS)
        thread = null
    }

    private companion object {
        const val JOIN_TIMEOUT_MS = 500L
        const val MIN_FPS = 30
        const val MAX_FPS = 120
        const val DEFAULT_FPS = 60
    }
}
