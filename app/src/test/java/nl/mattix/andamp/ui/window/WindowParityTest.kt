// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.window

import android.app.Application
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ApplicationProvider
import nl.mattix.andamp.skin.Skin
import nl.mattix.andamp.skin.SkinLoader
import nl.mattix.andamp.state.LibraryAccess
import nl.mattix.andamp.state.WinampViewModel
import nl.mattix.andamp.state.WindowStore
import nl.mattix.andamp.ui.LibraryAccessHandle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
 * What every floating window owes the others, asserted against every one of them.
 *
 * The shared contract lives here as a list of windows ([WindowCase.ALL]) and a handful of
 * assertions run against each: publishing a rectangle, measuring the shared screen, the
 * close button's hit slop, and a grip that grows the window away from its top-left corner.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE) // the skin decodes through BitmapFactory
@Config(sdk = [35], qualifiers = WIDE_WINDOW)
class WindowParityTest(
    private val case: WindowCase,
) {
    @get:Rule
    val compose = createComposeRule()

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var skin: Skin
    private lateinit var vm: WinampViewModel
    private var view: View? = null

    /** Set by a window's own close button, whatever that means for it. */
    private var closed = false

    @Before
    fun setUp() {
        skin = SkinLoader.loadBase(app)
        vm = testViewModel()
        case.open(vm)
        compose.setContent {
            view = LocalView.current
            Box(Modifier.fillMaxSize()) { case.content(vm, skin, SCALE) { closed = true } }
        }
        compose.waitForIdle()
    }

    private fun rect(): IntRect = vm.state.windowRects[case.id] ?: error("${case.label} never published a rectangle")

    private fun device(
        x: Int,
        y: Int,
    ) = Offset(x * SCALE + 1f, y * SCALE + 1f)

    /** A phone's gesture bar, handed to the window tree as the system would. */
    private fun dispatchGestureBar() {
        val insets =
            WindowInsetsCompat
                .Builder()
                .setInsets(WindowInsetsCompat.Type.systemGestures(), Insets.of(0, 0, 0, GESTURE_BAR))
                .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, GESTURE_BAR))
                .build()
        compose.runOnUiThread { ViewCompat.dispatchApplyWindowInsets(view!!.rootView, insets) }
        compose.waitForIdle()
    }

    @Test
    fun `it publishes a rectangle and a handle for its neighbors`() {
        // what docking, carrying and stacking all read; a window that keeps
        // its geometry to itself cannot be docked to
        assertNotNull("${case.label} publishes a rectangle", vm.state.windowRects[case.id])
        assertNotNull("${case.label} publishes a handle height", vm.state.windowHandles[case.id])
        assertTrue("${case.label} publishes a non-empty rectangle", rect().width > 0 && rect().height > 0)
    }

    @Test
    fun `it measures the screen every other window measures`() {
        dispatchGestureBar()
        val root = compose.onRoot().fetchSemanticsNode().size

        // the shared space: the whole of the app's window. What the gesture
        // bar takes is a safe bottom, never a smaller screen: a window that
        // published a shorter one would move its neighbors by half the
        // difference
        assertEquals("${case.label} measures the shared screen width", root.width / SCALE, vm.state.screenW)
        assertEquals("${case.label} measures the shared screen height", root.height / SCALE, vm.state.screenH)
        assertEquals(
            "${case.label} keeps the gesture bar as a safe bottom",
            (root.height - GESTURE_BAR) / SCALE,
            vm.state.safeBottom,
        )
    }

    @Test
    fun `a tap a hair wide of its close button still closes it`() {
        assumeTrue("${case.label} has no close button", case.hasClose)
        val at = rect()
        // Every window wears the same button in the same corner: 9x9, two
        // pixels in from the right edge, three down. The probe comes from
        // below, because the player and the playlist carry a shade button
        // right beside it.
        val closeMiddle = at.left + at.width - CLOSE_INSET + CLOSE_W / 2
        compose.onRoot().performTouchInput {
            down(device(closeMiddle, at.top + CLOSE_TOP + CLOSE_W + 2))
            up()
        }
        compose.waitForIdle()

        assertTrue("the near miss closes ${case.label}", closed)
    }

    @Test
    fun `the grip grows it away from its top-left corner`() {
        assumeTrue("${case.label} has no grip", case.resizable)
        val before = rect()
        val x = before.left + before.width - RESIZE_GRIP / 2
        val y = before.top + before.height - RESIZE_GRIP / 2

        // one event per frame, as in a real drag: a window that anchors
        // against its live size re-centers itself as each step lands
        compose.onRoot().performTouchInput { down(device(x, y)) }
        compose.waitForIdle()
        compose.onRoot().performTouchInput { moveTo(device(x + 30, y + 30)) }
        compose.waitForIdle()
        compose.onRoot().performTouchInput { moveTo(device(x + 60, y + 60)) }
        compose.waitForIdle()
        compose.onRoot().performTouchInput { up() }
        compose.waitForIdle()

        val after = rect()
        assertEquals("${case.label} keeps its left edge", before.left, after.left)
        assertEquals("${case.label} keeps its top edge", before.top, after.top)
        assertTrue("${case.label} grows downward", after.height > before.height)
        if (case.widthResizable) {
            assertTrue("${case.label} grows sideways", after.width > before.width)
        } else {
            assertEquals("${case.label} keeps its width", before.width, after.width)
        }
    }

    @Test
    fun `its grip is a hole in the back gesture`() {
        assumeTrue("${case.label} has no grip", case.resizable)
        val at = rect()
        val grip =
            android.graphics.Rect(
                (at.left + at.width - RESIZE_GRIP) * SCALE,
                (at.top + at.height - RESIZE_GRIP) * SCALE,
                (at.left + at.width) * SCALE,
                (at.top + at.height) * SCALE,
            )

        // A grip against the right edge of the screen is inside the strip the
        // system watches for the back gesture, so a drag there navigates away
        // instead of resizing. Android lets an app cut holes in that strip;
        // every grip asks for its own.
        val holes =
            compose.onRoot().fetchSemanticsNode().root!!.let { view ->
                (view as android.view.View).systemGestureExclusionRects
            }
        assertTrue(
            "${case.label} excludes its grip from the gesture strip: $holes",
            holes.any { it.contains(grip.centerX(), grip.centerY()) },
        )
    }

    companion object {
        /** Winamp's close button sits in the same corner on every window. */
        private const val CLOSE_INSET = 11
        private const val CLOSE_TOP = 3
        private const val CLOSE_W = 9

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun windows(): List<Array<Any>> = WindowCase.ALL.map { arrayOf<Any>(it) }
    }
}

/**
 * One window, as the app composes it and as this suite can drive it.
 *
 * [content] takes the close callback rather than reading state, because what
 * "closed" means differs per window - the player leaves, the equalizer hides,
 * the browser shuts - and the parity check only cares that the button answered.
 */
class WindowCase(
    val id: String,
    val label: String,
    val hasClose: Boolean = true,
    val resizable: Boolean = false,
    val widthResizable: Boolean = false,
    val open: (WinampViewModel) -> Unit = {},
    val content: @Composable (WinampViewModel, Skin, Int, () -> Unit) -> Unit,
) {
    override fun toString() = label

    companion object {
        val ALL =
            listOf(
                WindowCase(WindowStore.MAIN, "player") { vm, skin, scale, onClose ->
                    MainFloatWindow(
                        vm,
                        skin,
                        scale,
                        widgets = mainWindowWidgets(vm, onExit = onClose) {},
                        height = MAIN_H,
                        defaultOffset = IntOffset.Zero,
                        modifier = Modifier.fillMaxSize(),
                    )
                },
                WindowCase(WindowStore.EQ, "equalizer") { vm, skin, scale, onClose ->
                    EqFloatWindow(
                        vm,
                        skin,
                        scale,
                        widgets = eqWindowWidgets(vm),
                        height = EQ_H,
                        defaultOffset = IntOffset(0, MAIN_H),
                        modifier = Modifier.fillMaxSize(),
                    )
                    CloseWatch(vm, onClose) { !it.state.eqVisible }
                },
                WindowCase(
                    WindowStore.PLAYLIST,
                    "playlist",
                    resizable = true,
                    widthResizable = true,
                ) { vm, skin, scale, onClose ->
                    PlaylistFloatWindow(
                        vm,
                        skin,
                        scale,
                        PlaylistMenuActions(),
                        dockedTop = 0,
                        dockedSegments = PlaylistLayout.MIN_SEGMENTS + 2,
                        modifier = Modifier.fillMaxSize(),
                    )
                    CloseWatch(vm, onClose) { !it.state.plVisible }
                },
                WindowCase(
                    WindowStore.MILKDROP,
                    "plug-in",
                    resizable = true,
                    widthResizable = true,
                    open = { it.state.milkdropOn = true },
                ) { vm, skin, scale, onClose ->
                    MilkdropWindow(vm, skin, scale, defaultOffset = IntOffset.Zero, modifier = Modifier.fillMaxSize())
                    CloseWatch(vm, onClose) { !it.state.milkdropOn }
                },
                WindowCase(
                    WindowStore.SKINS,
                    "skin browser",
                    resizable = true,
                    widthResizable = true,
                    open = { it.state.skinManagerOpen = true },
                ) { vm, skin, scale, onClose ->
                    SkinManagerWindow(vm, skin, scale, Modifier.fillMaxSize())
                    CloseWatch(vm, onClose) { !it.state.skinManagerOpen }
                },
                WindowCase(
                    WindowStore.LIBRARY,
                    "library",
                    resizable = true,
                    widthResizable = true,
                    // opened at its floor, so the grip has somewhere to grow
                    open = {
                        it.state.libraryOpen = true
                        it.state.libraryRows = LibraryLayout.MIN_ROWS
                    },
                ) { vm, skin, scale, onClose ->
                    LibraryWindow(
                        vm,
                        skin,
                        scale,
                        LibraryAccessHandle(LibraryAccess.GRANTED, request = {}),
                        Modifier.fillMaxSize(),
                    )
                    CloseWatch(vm, onClose) { !it.state.libraryOpen }
                },
            )
    }
}

/** Reports a window closing itself through whatever state it owns. */
@Composable
private fun CloseWatch(
    vm: WinampViewModel,
    onClose: () -> Unit,
    gone: (WinampViewModel) -> Boolean,
) {
    if (gone(vm)) onClose()
}
