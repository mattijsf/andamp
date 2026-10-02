// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import android.app.Application
import android.os.Looper
import androidx.compose.ui.unit.IntOffset
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import nl.mattix.andamp.backend.mock.MockBackend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Where the windows were left is where they come back: a fresh player restores what the store
 * holds, and the layout on screen reaches the store through the debounced saver.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WindowPersistenceTest {
    private lateinit var app: Application

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        app
            .getSharedPreferences("windows", 0)
            .edit()
            .clear()
            .commit()
    }

    private fun player() =
        WinampViewModel(
            app,
            createBackend = { scope: CoroutineScope -> MockBackend(FakeTracks.tracks, scope) },
            presetStore = InMemoryEqPresetStore(),
        )

    @Test
    fun `a fresh player leaves every window where the layout puts it`() {
        val vm = player()

        assertNull(vm.state.plOffset)
        assertNull(vm.state.libraryRows)
        // the one exception: on a first run the playlist starts at a fixed height
        assertEquals(WindowStore.PLAYLIST_START_SEGMENTS, vm.state.plSegments)
        assertEquals(listOf("main", "eq", "milkdrop", "pl", "library", "skins"), vm.state.windowOrder)
    }

    @Test
    fun `positions, sizes and the stack come back`() {
        WindowStore(app).save(
            WindowLayoutMemory(
                placements =
                    mapOf(
                        WindowStore.PLAYLIST to WindowPlacement(x = 4, y = -20, size = 5),
                        WindowStore.LIBRARY to WindowPlacement(x = -8, y = 12, size = 11),
                        WindowStore.SKINS to WindowPlacement(x = 2, y = 2, size = null),
                    ),
                order = listOf(WindowStore.PLAYLIST, WindowStore.SKINS, WindowStore.LIBRARY),
            ),
        )

        val vm = player()

        assertEquals(IntOffset(4, -20), vm.state.plOffset)
        assertEquals(5, vm.state.plSegments)
        assertEquals(IntOffset(-8, 12), vm.state.libraryOffset)
        assertEquals(11, vm.state.libraryRows)
        assertEquals(IntOffset(2, 2), vm.state.skinManagerOffset)
        assertNull(vm.state.skinRows)
        // what the store named comes first, in its order; the rest keep theirs
        assertEquals(
            listOf(WindowStore.PLAYLIST, WindowStore.SKINS, WindowStore.LIBRARY),
            vm.state.windowOrder.filter { it in WindowStore.WINDOWS.take(6) && it !in listOf("main", "eq", "milkdrop") },
        )
    }

    @Test
    fun `a stored stack that names only some windows keeps the rest`() {
        WindowStore(app).save(WindowLayoutMemory(order = listOf(WindowStore.LIBRARY)))

        val vm = player()

        // the library was named, so it goes first; the rest keep their order
        assertEquals(WindowStore.LIBRARY, vm.state.windowOrder.first())
        assertEquals(WindowStore.WINDOWS.toSet(), vm.state.windowOrder.toSet())
    }

    @Test
    fun `a window left somewhere else is stored, and the next launch restores it`() {
        val first = player()
        first.state.plOffset = IntOffset(1, 2)
        first.state.plSegments = 7
        first.state.raiseWindow(WindowStore.LIBRARY)

        // the saver is debounced; Robolectric's clock has to be moved on for it
        await {
            // open too: a placement also says whether the window was on screen
            WindowStore(app).load().placementOf(WindowStore.PLAYLIST) ==
                WindowPlacement(1, 2, 7, open = true, shaded = false, cols = 0)
        }

        val next = player()
        assertEquals(IntOffset(1, 2), next.state.plOffset)
        assertEquals(7, next.state.plSegments)
        assertEquals(WindowStore.LIBRARY, next.state.windowOrder.last())
    }

    /**
     * Robolectric's looper runs on a simulated clock; the debounce needs it moved on. Snapshot
     * writes made outside a composition also need pushing, as there is no frame loop here.
     */
    private fun await(condition: () -> Boolean) {
        repeat(40) {
            androidx.compose.runtime.snapshots.Snapshot
                .sendApplyNotifications()
            if (condition()) return
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100))
            Thread.sleep(5)
        }
        assertTrue("the layout reaches the store within the wait", condition())
    }

    @Test
    fun `the visualizer window comes back the way it was left`() {
        WindowStore(app).save(
            WindowLayoutMemory(placements = mapOf(WindowStore.MILKDROP to WindowPlacement(open = true))),
        )

        assertTrue("the visualizer window comes back open", player().state.milkdropOn)
    }

    @Test
    fun `a visualizer window that was closed stays closed`() {
        WindowStore(app).save(
            WindowLayoutMemory(placements = mapOf(WindowStore.MILKDROP to WindowPlacement(open = false))),
        )

        assertEquals(false, player().state.milkdropOn)
    }

    @Test
    fun `opening the visualizer window is part of the layout that gets saved`() {
        val vm = player()

        vm.state.milkdropOn = true

        await { WindowStore(app).load().placementOf(WindowStore.MILKDROP).open == true }

        assertEquals(true, WindowStore(app).load().placementOf(WindowStore.MILKDROP).open)
    }

    @Test
    fun `the visualizer comes back showing what it was showing`() {
        VisualsStore(app).save(Visuals(mode = VisMode.Oscilloscope))

        assertEquals(VisMode.Oscilloscope, player().state.visMode)
    }

    @Test
    fun `switching the in-player visual off is remembered`() {
        val vm = player()

        vm.state.visMode = VisMode.Off

        await { VisualsStore(app).load().mode == VisMode.Off }
        assertEquals(VisMode.Off, VisualsStore(app).load().mode)
    }

    @Test
    fun `an equalizer and a playlist that were closed stay closed`() {
        WindowStore(app).save(
            WindowLayoutMemory(
                placements =
                    mapOf(
                        WindowStore.EQ to WindowPlacement(open = false),
                        WindowStore.PLAYLIST to WindowPlacement(open = false),
                    ),
            ),
        )

        val vm = player()

        assertEquals(false, vm.state.eqVisible)
        assertEquals(false, vm.state.plVisible)
    }

    @Test
    fun `closing the equalizer is part of the layout that gets saved`() {
        val vm = player()

        vm.state.eqVisible = false

        await { WindowStore(app).load().placementOf(WindowStore.EQ).open == false }
        assertEquals(false, WindowStore(app).load().placementOf(WindowStore.EQ).open)
    }

    @Test
    fun `a window left collapsed comes back collapsed`() {
        WindowStore(app).save(
            WindowLayoutMemory(
                placements =
                    mapOf(
                        WindowStore.MAIN to WindowPlacement(shaded = true),
                        WindowStore.EQ to WindowPlacement(shaded = true),
                        WindowStore.PLAYLIST to WindowPlacement(shaded = true),
                    ),
            ),
        )

        val vm = player()

        assertTrue(vm.state.mainShaded)
        assertTrue(vm.state.eqShaded)
        assertTrue(vm.state.plShaded)
    }

    @Test
    fun `collapsing a window is part of the layout that gets saved`() {
        val vm = player()

        vm.state.mainShaded = true

        await { WindowStore(app).load().placementOf(WindowStore.MAIN).shaded == true }
        assertEquals(true, WindowStore(app).load().placementOf(WindowStore.MAIN).shaded)
    }

    /**
     * Every window in [WindowStore.WINDOWS] has a placement state that takes and hands back an
     * offset, a size and width steps.
     */
    @Test
    fun `every window remembers where it was left`() {
        WindowStore.WINDOWS.forEachIndexed { i, id ->
            val fresh = WinampState()
            val placement = fresh.placementOf(id)
            assertNotNull("$id has a placement state", placement)

            placement!!.restore(WindowPlacement(x = 10 + i, y = 20 + i, size = 3 + i, cols = i))

            assertEquals("$id keeps its offset", IntOffset(10 + i, 20 + i), placement.offset)
            assertEquals("$id keeps its size", 3 + i, placement.size)
            assertEquals("$id keeps its width steps", i, placement.cols)
            val written = placement.asMemory()
            assertEquals("$id hands back the offset it took", 10 + i, written.x)
            assertEquals(20 + i, written.y)
            assertEquals(3 + i, written.size)
            assertEquals(i, written.cols)
        }
    }

    @Test
    fun `an empty memory leaves every window at its own default`() {
        val fresh = WinampState()

        WindowStore.WINDOWS.forEach { id ->
            val before = fresh.placementOf(id)!!.offset
            fresh.placementOf(id)!!.restore(WindowPlacement())
            assertEquals("$id stays put on an empty memory", before, fresh.placementOf(id)!!.offset)
        }
    }
}
