// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.wal

import nl.mattix.andamp.modernskin.skin.DrawCmd
import nl.mattix.andamp.modernskin.skin.DrawPlanner
import nl.mattix.andamp.modernskin.skin.EngineStats
import nl.mattix.andamp.modernskin.skin.ObjectInfo
import nl.mattix.andamp.modernskin.skin.Player
import nl.mattix.andamp.modernskin.skin.Presentation
import nl.mattix.andamp.modernskin.skin.Rect
import nl.mattix.andamp.modernskin.skin.SavedSettings
import nl.mattix.andamp.modernskin.skin.SkinEngine
import nl.mattix.andamp.modernskin.skin.SkinFiles
import nl.mattix.andamp.modernskin.skin.SkinHost
import nl.mattix.andamp.modernskin.skin.SkinMenu
import nl.mattix.andamp.modernskin.skin.SkinOptions
import nl.mattix.andamp.modernskin.skin.SkinSetup
import nl.mattix.andamp.modernskin.skin.WindowId
import nl.mattix.andamp.modernskin.skin.WindowInfo
import nl.mattix.andamp.wal.WalkitTypes.cmd
import nl.mattix.andamp.wal.WalkitTypes.edge
import nl.mattix.andamp.wal.WalkitTypes.entry
import nl.mattix.andamp.wal.WalkitTypes.font
import nl.mattix.andamp.wal.WalkitTypes.id
import nl.mattix.andamp.wal.WalkitTypes.menu
import nl.mattix.andamp.wal.WalkitTypes.plan
import nl.mattix.andamp.wal.WalkitTypes.rect
import nl.mattix.andamp.wal.WalkitTypes.wk
import java.util.IdentityHashMap
import nl.mattix.walkit.DrawCmd as WkDrawCmd
import nl.mattix.walkit.FontDef as WkFontDef
import nl.mattix.walkit.Player as WkPlayer
import nl.mattix.walkit.Presentation as WkPresentation
import nl.mattix.walkit.Rect as WkRect
import nl.mattix.walkit.SavedSettings as WkSavedSettings
import nl.mattix.walkit.SkinFiles as WkSkinFiles
import nl.mattix.walkit.SkinHost as WkSkinHost
import nl.mattix.walkit.SkinMenu as WkSkinMenu
import nl.mattix.walkit.SkinOptions as WkSkinOptions
import nl.mattix.walkit.SkinRuntime as WkSkinRuntime
import nl.mattix.walkit.SkinSetup as WkSkinSetup
import nl.mattix.walkit.Track as WkTrack
import nl.mattix.walkit.WindowId as WkWindowId

/**
 * The native engine (walkit, `libwalkit.so` through the `nl.mattix.walkit` library) behind the
 * app's [SkinEngine]: the same skins, the same members, in the engine module's types. The app's
 * [SkinFiles], [SkinHost] and [Player] are handed to the native runtime through adapters, and what
 * it answers (windows, draw lists, menus, options, settings) is converted back, one value at a
 * time. The native engine plans its frames itself, and hands over only the commands that changed
 * since the frame before: a command unchanged is the same object, so it is converted once.
 *
 * Everything runs on the engine thread, as with the Kotlin engine; [close] frees the native side.
 */
@Suppress("TooManyFunctions") // the whole engine surface, forwarded
internal class WalkitEngine(
    files: SkinFiles,
    override val host: SkinHost,
    override val player: Player,
    setup: SkinSetup,
    clock: () -> Long = System::currentTimeMillis,
) : SkinEngine {
    private val rt =
        WkSkinRuntime(
            FilesAdapter(files),
            HostAdapter(host),
            PlayerAdapter(player),
            WkSkinSetup(setup.skinName, setup.haveLibrary, setup.hostActions),
            clock,
        )

    override var skinName: String
        get() = rt.skinName
        set(value) {
            rt.skinName = value
        }
    override val settingsVersion: Int get() = rt.settingsVersion

    override fun savedSettings(): SavedSettings = rt.savedSettings().let { SavedSettings(it.attributes, it.strings, it.ints) }

    override fun restoreSettings(saved: SavedSettings) =
        rt.restoreSettings(WkSavedSettings(saved.attributes, saved.strings, saved.ints))

    override val configVersion: Int get() = rt.configVersion

    override fun skinOptions(): SkinOptions = rt.skinOptions().let { SkinOptions(it.version, it.entries.map(::entry)) }

    override fun toggleSkinOption(key: SkinOptions.Key): Boolean = rt.toggleSkinOption(WkSkinOptions.Key(key.item, key.attribute))

    override val viewport: Pair<Int, Int> get() = rt.viewport
    override var presentation: Presentation
        get() =
            rt.presentation.let {
                Presentation(
                    it.viewSize,
                    it.viewport,
                    it.columns,
                    it.columnWidth,
                    it.stretchLast,
                    it.touchSlop,
                )
            }
        set(value) {
            rt.presentation =
                WkPresentation(
                    value.viewSize,
                    value.viewport,
                    value.columns,
                    value.columnWidth,
                    value.stretchLast,
                    value.touchSlop,
                )
        }

    override fun load() = rt.load()

    override val colorThemes: List<String> get() = rt.colorThemes
    override val colorTheme: String? get() = rt.colorTheme

    override fun selectColorTheme(name: String) = rt.selectColorTheme(name)

    override val windowIds: List<WindowId> get() = rt.windowIds.map(::id)
    override val mainWindow: WindowId? get() = rt.mainWindow?.let(::id)

    override fun windowInfo(id: WindowId): WindowInfo =
        rt.windowInfo(wk(id)).let {
            WindowInfo(
                id(it.id),
                it.size,
                it.minW,
                it.minH,
                it.maxW,
                it.maxH,
                it.resizableBothWays,
                it.edgeX?.let(::edge),
                it.edgeY?.let(::edge),
                it.container,
                it.layout,
            )
        }

    override fun objectInfo(id: String): ObjectInfo? =
        rt.objectInfo(id)?.let { ObjectInfo(id(it.window), rect(it.rect), rect(it.stackRect), it.visible, it.className) }

    override fun windowOf(container: String): WindowId? = rt.windowOf(container)?.let(::id)

    override fun windowRect(id: WindowId): Rect = rect(rt.windowRect(wk(id)))

    override fun openWindow(container: String): Boolean = rt.openWindow(container)

    override fun closeWindow(container: String): Boolean = rt.closeWindow(container)

    override fun windowSize(id: WindowId): Pair<Int, Int> = rt.windowSize(wk(id))

    override fun resizeWindow(
        id: WindowId,
        width: Int,
        height: Int,
    ) = rt.resizeWindow(wk(id), width, height)

    override fun contentRight(
        id: WindowId,
        layOutFirst: Boolean,
    ): Int = rt.contentRight(wk(id), layOutFirst)

    override val listWindowOpen: Boolean get() = rt.listWindowOpen

    override fun shownWidth(width: Int): Int = rt.shownWidth(width)

    override fun naturalStackSize(
        columns: Int,
        columnWidth: Int,
    ): Pair<Int, Int>? = rt.naturalStackSize(columns, columnWidth)

    override fun leftColumn(): Rect? = rt.leftColumn()?.let(::rect)

    override val windowOpen: Boolean get() = rt.windowOpen
    override val edgePlacements: Int get() = rt.edgePlacements
    override val visRect: Rect? get() = rt.visRect?.let(::rect)

    override fun visualizerWindowClosed() = rt.visualizerWindowClosed()

    override fun closeTopWindow() = rt.closeTopWindow()

    override val stackSize: Pair<Int, Int> get() = rt.stackSize
    override val drawnSize: Pair<Int, Int> get() = rt.drawnSize

    override fun now(): Long = rt.now()

    override fun invalidate() = rt.invalidate()

    override fun ensureLayout() = rt.ensureLayout()

    override val sliding: Boolean get() = rt.sliding
    override val animating: Boolean get() = rt.animating
    override val framesWanted: Boolean get() = rt.framesWanted

    override fun nextDueAt(): Long? = rt.nextDueAt()

    override fun tick(slideLead: Long): Boolean = rt.tick(slideLead)

    override fun tick(): Boolean = rt.tick()

    override val stats: EngineStats get() =
        rt.stats.let {
            EngineStats(it.scriptSteps, it.timerFires, it.layoutPasses, it.shapesWorkedOut)
        }

    override fun drawWindows(): List<Pair<Rect, List<DrawCmd>>> = convert(rt.drawWindows())

    override fun drawPlanned(nowMs: Long): DrawPlanner.Planned =
        rt.drawPlanned(nowMs).let { DrawPlanner.Planned(convert(it.stack), it.plans.map(::plan)) }

    override val planNanos: Long get() = rt.planNanos
    override val fullPlans: Long get() = rt.fullPlans

    /** Nanoseconds spent converting the native frames' commands, over every frame so far; for timing runs. */
    var convertNanos: Long = 0L
        private set

    /** Nanoseconds in the native draw call, and reading its frames, over every frame so far; for timing runs. */
    val nativeDrawNanos: Long get() = rt.nativeDrawNanos
    val decodeNanos: Long get() = rt.decodeNanos

    /** The native frames' bytes, over every frame so far; for timing runs. */
    val frameBytes: Long get() = rt.frameBytes

    /** The frame before's commands as converted, by the library's object, so a command it kept is converted once. */
    private var converted = IdentityHashMap<WkDrawCmd, DrawCmd>()

    private fun convert(stack: List<Pair<WkRect, List<WkDrawCmd>>>): List<Pair<Rect, List<DrawCmd>>> {
        val t0 = System.nanoTime()
        val next = IdentityHashMap<WkDrawCmd, DrawCmd>(converted.size.coerceAtLeast(MIN_CONVERTED) * 2)
        val out = stack.map { (r, cmds) -> rect(r) to cmds.map { c -> next.getOrPut(c) { converted[c] ?: cmd(c) } } }
        converted = next
        convertNanos += System.nanoTime() - t0
        return out
    }

    override fun pointerDown(
        x: Int,
        y: Int,
        seq: Int,
    ) = rt.pointerDown(x, y, seq)

    override fun pointerMove(
        x: Int,
        y: Int,
    ) = rt.pointerMove(x, y)

    override fun pointerUp(
        x: Int,
        y: Int,
    ) = rt.pointerUp(x, y)

    override fun doubleTap(
        x: Int,
        y: Int,
        seq: Int,
    ) = rt.doubleTap(x, y, seq)

    override fun pointerCancel() = rt.pointerCancel()

    override val menuTarget: Int? get() = rt.menuTarget
    override val moveTarget: Int? get() = rt.moveTarget

    override fun rightClick(seq: Int) = rt.rightClick(seq)

    override fun <T> gesture(
        fresh: Boolean,
        block: () -> T,
    ): T = rt.gesture(fresh, block)

    override fun menuChoice(menu: SkinMenu): Int = rt.menuChoice(wk(menu))

    override fun close() = rt.close()

    private companion object {
        const val MIN_CONVERTED = 64
    }

    /** The app's files as the native runtime reads them. */
    private class FilesAdapter(
        private val files: SkinFiles,
    ) : WkSkinFiles {
        override fun read(path: String): ByteArray? = files.read(path)

        override fun names(): List<String> = files.names()
    }

    /** The app's host as the native runtime asks it: the same questions, in the library's types. */
    private class HostAdapter(
        private val host: SkinHost,
    ) : WkSkinHost {
        override fun imageSize(file: String): Pair<Int, Int>? = host.imageSize(file)

        override fun pixel(
            file: String,
            x: Int,
            y: Int,
        ): Int? = host.pixel(file, x, y)

        override fun pixels(
            file: String,
            x: Int,
            y: Int,
            w: Int,
            h: Int,
        ): IntArray? = host.pixels(file, x, y, w, h)

        override fun textWidth(
            text: String,
            font: WkFontDef?,
            size: Int,
        ): Int = host.textWidth(text, font?.let(::font), size)

        override fun textWidth(
            text: String,
            font: WkFontDef?,
            size: Int,
            bold: Boolean,
        ): Int = host.textWidth(text, font?.let(::font), size, bold)

        override fun textAdvance(
            text: String,
            font: WkFontDef?,
            size: Int,
            bold: Boolean,
        ): Float = host.textAdvance(text, font?.let(::font), size, bold)

        override fun lineMetrics(
            font: WkFontDef?,
            size: Int,
            bold: Boolean,
        ): Pair<Float, Float>? = host.lineMetrics(font?.let(::font), size, bold)

        override fun log(message: String) = host.log(message)

        override fun invalidate() = host.invalidate()

        override fun windowOpen(kind: String): Boolean? = host.windowOpen(kind)

        override fun chooseFromMenu(menu: WkSkinMenu): Int? = host.chooseFromMenu(menu(menu))

        override fun windowsChanged(windows: List<WkWindowId>) = host.windowsChanged(windows.map(::id))

        override fun hostAction(
            action: String,
            param: String?,
        ) = host.hostAction(action, param)
    }

    /** The app's player as the native runtime drives it. */
    @Suppress("TooManyFunctions") // the player's whole surface, forwarded
    private class PlayerAdapter(
        private val player: Player,
    ) : WkPlayer {
        override val status: WkPlayer.Status
            get() =
                when (player.status) {
                    Player.Status.STOPPED -> WkPlayer.Status.STOPPED
                    Player.Status.PLAYING -> WkPlayer.Status.PLAYING
                    Player.Status.PAUSED -> WkPlayer.Status.PAUSED
                }
        override val positionMs: Int get() = player.positionMs
        override val lengthMs: Int get() = player.lengthMs
        override val title: String get() = player.title
        override val bitrateKbps: Int get() = player.bitrateKbps
        override val sampleRateKhz: Int get() = player.sampleRateKhz
        override val channels: Int get() = player.channels
        override val tags: Map<String, String> get() = player.tags
        override val decoderName: String get() = player.decoderName
        override var volume: Int
            get() = player.volume
            set(value) {
                player.volume = value
            }
        override var shuffle: Boolean
            get() = player.shuffle
            set(value) {
                player.shuffle = value
            }
        override var repeat: Boolean
            get() = player.repeat
            set(value) {
                player.repeat = value
            }
        override var crossfade: Boolean
            get() = player.crossfade
            set(value) {
                player.crossfade = value
            }
        override var eqEnabled: Boolean
            get() = player.eqEnabled
            set(value) {
                player.eqEnabled = value
            }
        override val eqBands: IntArray get() = player.eqBands
        override var eqPreamp: Int
            get() = player.eqPreamp
            set(value) {
                player.eqPreamp = value
            }
        override var pan: Int
            get() = player.pan
            set(value) {
                player.pan = value
            }

        override fun play() = player.play()

        override fun pause() = player.pause()

        override fun stop() = player.stop()

        override fun next() = player.next()

        override fun previous() = player.previous()

        override fun eject() = player.eject()

        override fun seekTo(ms: Int) = player.seekTo(ms)

        override fun setEqBand(
            band: Int,
            value: Int,
        ) = player.setEqBand(band, value)

        override val tracks: List<WkTrack> get() = player.tracks.map { WkTrack(it.title, it.lengthMs) }
        override val trackIndex: Int get() = player.trackIndex

        override fun playTrack(index: Int) = player.playTrack(index)

        override fun spectrum(): FloatArray = player.spectrum()

        override fun waveform(): FloatArray = player.waveform()

        override fun vu(): Pair<Int, Int> = player.vu()
    }
}
