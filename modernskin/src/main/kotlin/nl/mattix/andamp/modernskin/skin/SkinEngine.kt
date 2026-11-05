// SPDX-License-Identifier: GPL-3.0-or-later
package nl.mattix.andamp.modernskin.skin

/**
 * What a host of a modern skin drives, as [SkinRuntime] gives it: loading, the clock and ticks,
 * the windows and what each shows, the stack's measures and presentation, touches, menus, color
 * themes, the skin's settings and options, and the draw list of a frame, planned or not. The
 * engine in this module implements it; a host that brings another engine over the same skins (one
 * in native code, say) implements it too, in the same types, so the host kit ([nl.mattix.andamp.modernskin.host])
 * and the app's adapters run over either. Everything runs on one thread; see [SkinRuntime] for
 * what each member does.
 */
@Suppress("TooManyFunctions", "ComplexInterface") // the one surface a host drives a skin by
public interface SkinEngine {
    /** The host the skin asks for pictures, text, windows and menus. */
    public val host: SkinHost

    /** The player the skin shows. */
    public val player: Player

    /** What getSkinName() answers: the skin's folder name, which scripts key what they remember by. */
    public var skinName: String

    /** Counts changes to what a host keeps of the skin between runs. */
    public val settingsVersion: Int

    public fun savedSettings(): SavedSettings

    public fun restoreSettings(saved: SavedSettings)

    /** Counts changes to the skin's Config items; see [SkinOptions.version]. */
    public val configVersion: Int

    public fun skinOptions(): SkinOptions

    public fun toggleSkinOption(key: SkinOptions.Key): Boolean

    /** The screen as scripts ask for it. */
    public val viewport: Pair<Int, Int>

    /** How the host shows the windows, as one value. */
    public var presentation: Presentation

    /** Loads the skin, builds the windows and starts the scripts. */
    public fun load()

    public val colorThemes: List<String>

    public val colorTheme: String?

    public fun selectColorTheme(name: String)

    /** The windows on screen, the main one first. */
    public val windowIds: List<WindowId>

    public val mainWindow: WindowId?

    public fun windowInfo(id: WindowId): WindowInfo

    public fun objectInfo(id: String): ObjectInfo?

    public fun windowOf(container: String): WindowId?

    public fun windowRect(id: WindowId): Rect

    public fun openWindow(container: String): Boolean

    public fun closeWindow(container: String): Boolean

    public fun windowSize(id: WindowId): Pair<Int, Int>

    public fun resizeWindow(
        id: WindowId,
        width: Int,
        height: Int,
    )

    public fun contentRight(
        id: WindowId,
        layOutFirst: Boolean = false,
    ): Int

    public val listWindowOpen: Boolean

    public fun shownWidth(width: Int = 0): Int

    public fun naturalStackSize(
        columns: Int,
        columnWidth: Int = 0,
    ): Pair<Int, Int>?

    public fun leftColumn(): Rect?

    /** Whether a window besides the main one is open, which Back would put away. */
    public val windowOpen: Boolean

    public val edgePlacements: Int

    public val visRect: Rect?

    public fun visualizerWindowClosed()

    public fun closeTopWindow()

    public val stackSize: Pair<Int, Int>

    public val drawnSize: Pair<Int, Int>

    /** The clock the skin reads, in ms. */
    public fun now(): Long

    public fun invalidate()

    public fun ensureLayout()

    public val sliding: Boolean

    public val animating: Boolean

    public val framesWanted: Boolean

    public fun nextDueAt(): Long?

    public fun tick(slideLead: Long): Boolean

    public fun tick(): Boolean

    public val stats: EngineStats

    /** Every window's drawing with its place in the stack. */
    public fun drawWindows(): List<Pair<Rect, List<DrawCmd>>>

    /**
     * Every window's drawing as [drawWindows] gives it, with a plan of what a renderer may keep of
     * each from the frames before ([DrawPlanner]), at [nowMs] in any clock that only moves forward.
     */
    public fun drawPlanned(nowMs: Long): DrawPlanner.Planned

    /** Nanoseconds spent planning so far, the drawing itself left out; for timing runs. */
    public val planNanos: Long

    /** Windows planned from the start so far; for timing runs. */
    public val fullPlans: Long

    public fun pointerDown(
        x: Int,
        y: Int,
        seq: Int = 0,
    )

    public fun pointerMove(
        x: Int,
        y: Int,
    )

    public fun pointerUp(
        x: Int,
        y: Int,
    )

    public fun doubleTap(
        x: Int,
        y: Int,
        seq: Int = 0,
    )

    public fun pointerCancel()

    public val menuTarget: Int?

    public val moveTarget: Int?

    public fun rightClick(seq: Int)

    public fun <T> gesture(
        fresh: Boolean = false,
        block: () -> T,
    ): T

    public fun menuChoice(menu: SkinMenu): Int

    /** Frees what the engine holds outside the JVM, if anything; the runtime is not used after. */
    public fun close(): Unit = Unit
}
