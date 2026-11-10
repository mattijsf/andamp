// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.wal

import android.util.Log
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import nl.mattix.andamp.core.model.Transport
import nl.mattix.andamp.core.model.displayName
import nl.mattix.andamp.skin.Dest
import nl.mattix.andamp.skinplayer.WinampFiveUnits
import nl.mattix.andamp.state.WinampState
import nl.mattix.walkit.ClassicSwitches
import nl.mattix.walkit.ClassicVisMode
import nl.mattix.walkit.DrawCmd
import nl.mattix.walkit.Player
import nl.mattix.walkit.Rect
import nl.mattix.walkit.SkinRuntime
import nl.mattix.walkit.Track
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * A classic skin's main window as still pictures, drawn by the engine off screen, for what shows
 * the player without a player surface: the home screen widget.
 *
 * One skin runtime runs on a thread of its own (the host's caches belong to one thread), for the
 * file it was made for. Each [main] puts the [Still] it is given into the skin, steps the skin's
 * clock to [Still.atMs], and draws the main window at 1x. [close] lets the runtime go.
 */
internal class WalStill private constructor(
    /** The skin's file. */
    val file: File,
    private val cacheDir: File,
) {
    /** What the skin shows in one picture, on Winamp 2's scales as Andamp keeps them. */
    data class Still(
        val playing: Boolean = false,
        val paused: Boolean = false,
        val title: String = "",
        val positionMs: Int = 0,
        val lengthMs: Int = 0,
        val bitrateKbps: Int = 0,
        val sampleRateKhz: Int = 0,
        val channels: Int = 2,
        /** 0..100. */
        val volume: Int = 0,
        /** -100..100. */
        val balance: Int = 0,
        val shuffle: Boolean = false,
        val repeat: Boolean = false,
        val timeRemaining: Boolean = false,
        val vis: ClassicVisMode = ClassicVisMode.ANALYZER,
        /** 75 levels 0..1 as the player's skins read them, or empty for none. */
        val spectrum: FloatArray = FloatArray(0),
        /** 576 samples -1..1, or empty for none. */
        val waveform: FloatArray = FloatArray(0),
        /** The skin's clock for this picture, in ms: the marquee and the visualizer's falls follow it. */
        val atMs: Long = 0,
        /** A point of the main window held down in this picture, as a button flashes after a press. */
        val pressAt: Pair<Int, Int>? = null,
        /** Whether the main window shows its shade. */
        val shaded: Boolean = false,
        /** The window drawn: [MAIN] or [EQUALIZER], the other windows closed. */
        val window: String = MAIN,
        val eqOn: Boolean = false,
        val eqAuto: Boolean = false,
        /** 0..63, as Andamp's equalizer keeps it. */
        val preamp: Int = EQ_FLAT,
        /** Ten bands, 0..63 each. */
        val bands: List<Int> = List(EQ_BANDS) { EQ_FLAT },
    )

    private val thread: ExecutorService = Executors.newSingleThreadExecutor { Thread(it, "wal-still") }
    private val player = StillPlayer()
    private var host: WalHost? = null
    private var runtime: SkinRuntime? = null
    private var opened: WalFiles.Opened? = null

    @Volatile
    private var closed = false

    /**
     * The window [Still.window] as [still] has it, at 1x, or null when the skin has none or does
     * not load. Blocks until it is drawn.
     */
    fun main(still: Still): ImageBitmap? = on { draw(still) }

    /** The main window's size at 1x as it shows now, or null when the skin has none or does not load. */
    fun mainSize(): Pair<Int, Int>? = on { runtime()?.let { rt -> rt.mainWindow?.let(rt::windowSize) } }

    /** Lets the runtime go; nothing is drawn after. */
    fun close() {
        if (closed) return
        closed = true
        thread.execute {
            runtime?.close()
            opened?.close()
            runtime = null
            host = null
        }
        thread.shutdown()
    }

    private fun <T> on(work: () -> T?): T? {
        if (closed) return null
        return runCatching { thread.submit(Callable { work() }).get() }
            .onFailure { Log.w(TAG, "a still of ${file.name} failed", it) }
            .getOrNull()
    }

    /** The runtime, made and loaded the first time. */
    private fun runtime(): SkinRuntime? {
        runtime?.let { return it }
        val files = WalFiles.open(file, emptyMap<String, File>()).also { opened = it }
        val made = WalHost(files, cacheDir).apply { crispText = WalCrispText(::crispFontBytes) }
        host = made
        return SkinRuntime(files, made, player, WalPrepare.setup(""), clock = { player.atMs }).apply { load() }.also {
            runtime = it
        }
    }

    private fun draw(still: Still): ImageBitmap? {
        player.still = still
        val rt = runtime() ?: return null
        rt.classicSwitches = ClassicSwitches(timeRemaining = still.timeRemaining, eqAuto = still.eqAuto, visMode = still.vis)
        // the window shown alone: the main window's EQ and PL buttons light only for an open window
        if (still.window == EQUALIZER) rt.openWindow(EQUALIZER) else OTHERS.forEach(rt::closeWindow)
        // as a double click on the title bar switches it, in Winamp
        if (shaded(rt) != still.shaded) {
            rt.doubleTap(TITLE_X, TITLE_Y)
            rt.pointerUp(TITLE_X, TITLE_Y)
        }
        rt.tick()
        still.pressAt?.let { (x, y) -> rt.pointerDown(x, y) }
        val stack = rt.drawWindows()
        if (still.pressAt != null) rt.pointerCancel()
        val rect = (if (still.window == MAIN) rt.mainWindow else rt.windowOf(still.window))?.let(rt::windowRect)
        val drawHost = host
        return if (rect == null || drawHost == null) null else paint(stack.filter { it.first == rect }, rect, drawHost)
    }

    /** [window] at 1x, from [rect]'s corner; null when it is empty or has no size. */
    private fun paint(
        window: List<Pair<Rect, List<DrawCmd>>>,
        rect: Rect,
        drawHost: WalHost,
    ): ImageBitmap? {
        if (window.isEmpty() || rect.width <= 0 || rect.height <= 0) return null
        val image = ImageBitmap(rect.width, rect.height)
        CanvasDrawScope().draw(
            Density(1f),
            LayoutDirection.Ltr,
            Canvas(image),
            Size(rect.width.toFloat(), rect.height.toFloat()),
        ) {
            translate(-rect.left.toFloat(), -rect.top.toFloat()) { drawWalStack(window, drawHost, 1f, 0f, layer = null) }
        }
        return image
    }

    /** Whether the main window shows its shade layout now. */
    private fun shaded(rt: SkinRuntime): Boolean = rt.mainWindow?.let { rt.windowInfo(it).layout == SHADE } == true

    private fun crispFontBytes(
        font: nl.mattix.walkit.FontDef?,
        bold: Boolean,
    ): ByteArray? = host?.crispFontBytes(font, bold)

    /** The player the still's skin reads: what [still] says, and nothing it is asked to do. */
    private class StillPlayer : Player {
        @Volatile
        var still = Still()

        val atMs: Long get() = still.atMs

        override val status: Player.Status
            get() =
                when {
                    still.playing -> Player.Status.PLAYING
                    still.paused -> Player.Status.PAUSED
                    else -> Player.Status.STOPPED
                }
        override val positionMs: Int get() = still.positionMs
        override val lengthMs: Int get() = still.lengthMs
        override val title: String get() = still.title
        override val bitrateKbps: Int get() = still.bitrateKbps
        override val sampleRateKhz: Int get() = still.sampleRateKhz
        override val channels: Int get() = still.channels
        override val tracks: List<Track> get() = listOf(Track(title, lengthMs))
        override var volume: Int
            get() = WinampFiveUnits.fromPercent(still.volume)
            set(_) = Unit
        override var pan: Int
            get() = WinampFiveUnits.fromBalance(still.balance)
            set(_) = Unit
        override var shuffle: Boolean
            get() = still.shuffle
            set(_) = Unit
        override var repeat: Boolean
            get() = still.repeat
            set(_) = Unit
        override var crossfade: Boolean
            get() = false
            set(_) = Unit
        override var eqEnabled: Boolean
            get() = still.eqOn
            set(_) = Unit
        override val eqBands: IntArray get() =
            IntArray(
                EQ_BANDS,
            ) { WinampFiveUnits.fromEq(still.bands.getOrElse(it) { EQ_FLAT }) }
        override var eqPreamp: Int
            get() = WinampFiveUnits.fromEq(still.preamp)
            set(_) = Unit

        override fun play() = Unit

        override fun pause() = Unit

        override fun stop() = Unit

        override fun next() = Unit

        override fun previous() = Unit

        override fun eject() = Unit

        override fun seekTo(ms: Int) = Unit

        override fun setEqBand(
            band: Int,
            value: Int,
        ) = Unit

        override fun spectrum(): FloatArray = if (still.playing) still.spectrum else EMPTY

        override fun waveform(): FloatArray = if (still.playing) still.waveform else EMPTY

        override fun vu(): Pair<Int, Int> = 0 to 0
    }

    companion object {
        private const val TAG = "WalStill"
        private const val EQ_BANDS = 10

        /** A band at its middle, on Andamp's 0..63. */
        private const val EQ_FLAT = 32

        /** The main window's container. */
        const val MAIN = "main"

        /** The equalizer's container. */
        const val EQUALIZER = "equalizer"

        /** The windows closed while the main window is drawn alone. */
        private val OTHERS = listOf(EQUALIZER, "pledit")

        // a point of the main window's title bar that is title bar in its shade too, clear of the
        // shade's visualizer and clock
        private const val TITLE_X = 40
        private const val TITLE_Y = 6

        /** The main window's shade layout, by its id in the skin. */
        private const val SHADE = "shade"
        private val EMPTY = FloatArray(0)

        private val kept = HashMap<String, WalStill>()

        /**
         * The still of [file] that [user] draws with, made when the one kept for it is of another
         * file, which is let go. One is kept per user per process: the widget redraws every second,
         * a car's screen every frame, and loading a skin takes longer.
         */
        @Synchronized
        fun of(
            file: File,
            cacheDir: File,
            user: String = WIDGET,
        ): WalStill {
            kept[user]?.takeIf { it.file == file && !it.closed }?.let { return it }
            kept[user]?.close()
            return WalStill(file, cacheDir).also { kept[user] = it }
        }

        /** The home screen widget's still. */
        const val WIDGET = "widget"

        /** The still of the screen a car lends the player. */
        const val CAR = "car"

        /**
         * What [state] shows, at [atMs] on the skin's clock, in [window]: the song, the clock, the
         * readout, the sliders, the switches, the equalizer, and the control pressed last held down
         * (see [pressPoint]).
         */
        fun stillOf(
            state: WinampState,
            atMs: Long,
            shaded: Boolean = false,
            window: String = MAIN,
        ): Still {
            val track = state.currentTrack
            return Still(
                playing = state.transport == Transport.Playing,
                paused = state.transport == Transport.Paused,
                title = track?.displayName.orEmpty(),
                positionMs = state.currentTimeSec * MS_PER_SECOND,
                lengthMs = (track?.durationMs ?: 0L).toInt(),
                bitrateKbps = state.bitrateKbps ?: 0,
                sampleRateKhz = state.sampleRateKhz ?: 0,
                channels = state.streamChannels ?: 2,
                volume = state.volume,
                balance = state.balance,
                shuffle = state.shuffle,
                repeat = state.repeat,
                timeRemaining = state.timeRemaining,
                atMs = atMs,
                pressAt = state.pressedWidget?.let { pressPoint(it, state) }.takeIf { window == MAIN },
                shaded = shaded,
                window = window,
                eqOn = state.eqOn,
                eqAuto = state.eqAuto,
                preamp = state.preamp,
                bands = state.eqBands.toList(),
            )
        }

        /**
         * A point inside the main window's control the app's windows name [id], to hold it down
         * for its flash: a button, or a slider's thumb where [state] has it, so holding it does not
         * move it.
         */
        private fun pressPoint(
            id: String,
            state: WinampState,
        ): Pair<Int, Int>? =
            when (id) {
                "main.posbar" -> {
                    POSBAR_X + (state.positionFraction * (POSBAR_W - POSBAR_THUMB)).toInt() + POSBAR_THUMB / 2 to
                        POSBAR_Y + INSIDE
                }

                "main.volume" -> {
                    VOLUME_X + state.volume * (VOLUME_W - VOLUME_THUMB) / VOLUME_MAX + VOLUME_THUMB / 2 to VOLUME_Y + INSIDE
                }

                else -> {
                    when (id) {
                        "main.prev" -> Dest.PREV
                        "main.play" -> Dest.PLAY
                        "main.pause" -> Dest.PAUSE
                        "main.stop" -> Dest.STOP
                        "main.next" -> Dest.NEXT
                        "main.shuffle" -> Dest.SHUFFLE
                        "main.repeat" -> Dest.REPEAT
                        else -> null
                    }?.let { it.x + INSIDE to it.y + INSIDE }
                }
            }

        private const val MS_PER_SECOND = 1000

        /** How far inside a button's corner the point that holds it lies. */
        private const val INSIDE = 5

        // the seek bar and the volume slider of Winamp 2's main window, and their thumbs
        private const val POSBAR_X = 16
        private const val POSBAR_Y = 72
        private const val POSBAR_W = 248
        private const val POSBAR_THUMB = 29
        private const val VOLUME_X = 107
        private const val VOLUME_Y = 57
        private const val VOLUME_W = 68
        private const val VOLUME_THUMB = 14
        private const val VOLUME_MAX = 100
    }
}
