// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.window

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.test.core.app.ApplicationProvider
import nl.mattix.andamp.skin.Skin
import nl.mattix.andamp.skin.SkinLoader
import nl.mattix.andamp.state.LibraryAccess
import nl.mattix.andamp.state.WinampViewModel
import nl.mattix.andamp.state.WindowPlacement
import nl.mattix.andamp.state.WindowStore
import nl.mattix.andamp.ui.LibraryAccessHandle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * What every window owes the layout that holds it in place, asserted against each of them.
 *
 * A held window is drawn in the rectangle it was given, at any height, stays there under
 * the finger, and leaves the place and the size it floats at exactly as they were.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE) // the skin decodes through BitmapFactory
@Config(sdk = [35], qualifiers = WIDE_WINDOW)
class PinnedWindowParityTest(
    private val case: PinnedCase,
) {
    @get:Rule
    val compose = createComposeRule()

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var skin: Skin
    private lateinit var vm: WinampViewModel
    private lateinit var pinned: IntRect
    private lateinit var floatingLayout: List<Any?>

    /** What the plug-in window's grip asked for; the other windows never ask. */
    private val stepsAsked = mutableListOf<Int>()

    @Before
    fun setUp() {
        skin = SkinLoader.loadBase(app)
        vm = testViewModel()
        case.open(vm)
        pinned = IntRect(LEFT, TOP, LEFT + MAIN_W, TOP + case.height(skin))
        floatingLayout = placesOf(stored())
        compose.setContent {
            Box(Modifier.fillMaxSize()) { case.content(vm, skin, SCALE, pinned) { stepsAsked += it } }
        }
        compose.waitForIdle()
    }

    /** Every window's placement as the store would save it. */
    private fun stored() = WindowStore.WINDOWS.map { vm.state.placementOf(it)?.asMemory() }

    /** Where and how large each window floats: a placement without whether it is collapsed. */
    private fun placesOf(placements: List<WindowPlacement?>) = placements.map { it?.copy(shaded = false) }

    private fun rect(): IntRect = vm.state.windowRects[case.id] ?: error("${case.label} never published a rectangle")

    private fun device(
        x: Int,
        y: Int,
    ) = Offset(x * SCALE + 1f, y * SCALE + 1f)

    private fun drag(
        fromX: Int,
        fromY: Int,
    ) {
        // one event per frame, as in a real drag
        compose.onRoot().performTouchInput { down(device(fromX, fromY)) }
        compose.waitForIdle()
        compose.onRoot().performTouchInput { moveTo(device(fromX + 30, fromY + 40)) }
        compose.waitForIdle()
        compose.onRoot().performTouchInput { moveTo(device(fromX + 60, fromY + 80)) }
        compose.waitForIdle()
        compose.onRoot().performTouchInput { up() }
        compose.waitForIdle()
    }

    @Test
    fun `it is drawn in the rectangle it was given`() {
        assertEquals("${case.label} publishes the rectangle it is held in", pinned, rect())
    }

    @Test
    fun `a drag on its title bar leaves it where it is`() {
        drag(pinned.left + 100, pinned.top + 5)

        assertEquals("${case.label} stays in its rectangle", pinned, rect())
        assertEquals("${case.label} keeps the place it floats at", floatingLayout, placesOf(stored()))
    }

    @Test
    fun `a drag on its corner leaves the size it floats at alone`() {
        drag(pinned.right - RESIZE_GRIP / 2, pinned.bottom - RESIZE_GRIP / 2)

        assertEquals("${case.label} stays in its rectangle", pinned, rect())
        assertEquals("${case.label} keeps the size it floats at", floatingLayout, placesOf(stored()))
        if (case.asksForSteps) {
            assertTrue("${case.label} asks the layout for more height: $stepsAsked", stepsAsked.last() > VIS_STEPS)
            assertTrue("${case.label} asks for no more than it may have", stepsAsked.all { it <= VIS_MAX_STEPS })
        } else {
            assertEquals("${case.label} has no grip", emptyList<Int>(), stepsAsked)
        }
    }

    @Test
    fun `two taps on its title bar still collapse or expand it`() {
        assumeTrue("${case.label} has no collapsed form", case.shades)
        val was = vm.state.isShaded(case.id)

        // one gesture block: two taps as close together as a finger makes them
        compose.onRoot().performTouchInput {
            down(device(pinned.left + 100, pinned.top + 5))
            up()
            advanceEventTime(60)
            down(device(pinned.left + 100, pinned.top + 5))
            up()
        }
        compose.waitForIdle()

        assertEquals("${case.label} answers the double tap", !was, vm.state.isShaded(case.id))
        assertEquals("${case.label} keeps the place it floats at", floatingLayout, placesOf(stored()))
    }

    companion object {
        private const val LEFT = 40
        private const val TOP = 60

        /** Not a whole number of the playlist's segments or of a list's rows. */
        private const val ODD_HEIGHT = 301
        private const val VIS_STEPS = 12
        private const val VIS_MAX_STEPS = 18

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun windows(): List<Array<Any>> = CASES.map { arrayOf<Any>(it) }

        private val CASES =
            listOf(
                PinnedCase(WindowStore.MAIN, "player", height = { MAIN_H }, shades = true) { vm, skin, scale, pinned, _ ->
                    MainFloatWindow(
                        vm,
                        skin,
                        scale,
                        widgets = mainWindowWidgets(vm) {},
                        height = MAIN_H,
                        defaultOffset = IntOffset.Zero,
                        modifier = Modifier.fillMaxSize(),
                        pinnedAt = pinned.topLeft,
                    )
                },
                PinnedCase(WindowStore.EQ, "equalizer", height = { EQ_H }, shades = true) { vm, skin, scale, pinned, _ ->
                    EqFloatWindow(
                        vm,
                        skin,
                        scale,
                        widgets = eqWindowWidgets(vm),
                        height = EQ_H,
                        defaultOffset = IntOffset.Zero,
                        modifier = Modifier.fillMaxSize(),
                        pinnedAt = pinned.topLeft,
                    )
                },
                PinnedCase(
                    WindowStore.PLAYLIST,
                    "playlist",
                    height = { ODD_HEIGHT },
                    shades = true,
                ) { vm, skin, scale, pinned, _ ->
                    PlaylistFloatWindow(
                        vm,
                        skin,
                        scale,
                        PlaylistMenuActions(),
                        dockedTop = 0,
                        dockedSegments = PlaylistLayout.MIN_SEGMENTS,
                        modifier = Modifier.fillMaxSize(),
                        pinned = pinned,
                    )
                },
                PinnedCase(
                    WindowStore.PLAYLIST,
                    "playlist bar",
                    height = { SHADE_H },
                    shades = true,
                    open = { it.state.plShaded = true },
                ) { vm, skin, scale, pinned, _ ->
                    PlaylistShadeFloatWindow(
                        vm,
                        skin,
                        scale,
                        expandedH = PlaylistLayout.ofSegments(PlaylistLayout.MIN_SEGMENTS).height,
                        defaultOffset = IntOffset.Zero,
                        modifier = Modifier.fillMaxSize(),
                        pinnedAt = pinned.topLeft,
                    )
                },
                PinnedCase(
                    WindowStore.MILKDROP,
                    "plug-in",
                    height = { milkdropHeight(frameFor(it), VIS_STEPS * MILKDROP_STEP) },
                    asksForSteps = true,
                    open = { it.state.milkdropOn = true },
                ) { vm, skin, scale, pinned, onSteps ->
                    MilkdropWindow(
                        vm,
                        skin,
                        scale,
                        defaultOffset = IntOffset.Zero,
                        modifier = Modifier.fillMaxSize(),
                        pinned = PinnedVisual(pinned, maxSteps = VIS_MAX_STEPS, onSteps = onSteps),
                    )
                },
                PinnedCase(
                    WindowStore.SKINS,
                    "skin browser",
                    height = { ODD_HEIGHT },
                    open = { it.state.skinManagerOpen = true },
                ) { vm, skin, scale, pinned, _ ->
                    SkinManagerWindow(vm, skin, scale, Modifier.fillMaxSize(), pinned = pinned)
                },
                PinnedCase(
                    WindowStore.LIBRARY,
                    "library",
                    height = { ODD_HEIGHT },
                    open = { it.state.libraryOpen = true },
                ) { vm, skin, scale, pinned, _ ->
                    LibraryWindow(
                        vm,
                        skin,
                        scale,
                        LibraryAccessHandle(LibraryAccess.GRANTED, request = {}),
                        Modifier.fillMaxSize(),
                        pinned = pinned,
                    )
                },
            )
    }
}

/** One window as the app composes it while the layout holds it in [content]'s rectangle. */
class PinnedCase(
    val id: String,
    val label: String,
    /** The height it is held at; the plug-in window's depends on the skin's frame. */
    val height: (Skin) -> Int,
    val asksForSteps: Boolean = false,
    /** Whether two taps on its title bar collapse or expand it. */
    val shades: Boolean = false,
    val open: (WinampViewModel) -> Unit = {},
    val content: @Composable (WinampViewModel, Skin, Int, IntRect, (Int) -> Unit) -> Unit,
) {
    override fun toString() = label
}
