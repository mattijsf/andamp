// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What the window layout remembers between launches. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WindowStoreTest {
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

    @Test
    fun `nothing stored means every window is where the layout puts it`() {
        val memory = WindowStore(app).load()

        val playlist = memory.placementOf(WindowStore.PLAYLIST)
        assertNull(playlist.of(Workspace.FLOATING).x)
        assertNull(playlist.of(Workspace.FLOATING).y)
        assertNull(playlist.of(Workspace.FLOATING).size)
        assertEquals(emptyList<String>(), memory.orderOf(Workspace.FLOATING))
    }

    /**
     * Every placement is null on a first run and also in a layout saved with every window at its
     * full size; only the schema key tells the two apart.
     */
    @Test
    fun `nothing stored is a first run, and a layout that was saved is not`() {
        assertTrue(WindowStore(app).load().firstRun)

        WindowStore(app).save(WindowLayoutMemory())

        assertFalse(WindowStore(app).load().firstRun)
    }

    @Test
    fun `placements and the stack survive a reload`() {
        val store = WindowStore(app)
        store.save(
            WindowLayoutMemory(
                placements =
                    mapOf(
                        WindowStore.PLAYLIST to
                            WindowPlacement(mapOf(Workspace.FLOATING to WindowArrangement(x = -12, y = 40, size = 6))),
                        WindowStore.LIBRARY to
                            WindowPlacement(mapOf(Workspace.FLOATING to WindowArrangement(x = 0, y = -30, size = null))),
                    ),
                orders = mapOf(Workspace.FLOATING to listOf(WindowStore.LIBRARY, WindowStore.PLAYLIST)),
            ),
        )

        val memory = WindowStore(app).load()

        assertEquals(WindowArrangement(-12, 40, 6), memory.placementOf(WindowStore.PLAYLIST).of(Workspace.FLOATING))
        assertEquals(WindowArrangement(0, -30, null), memory.placementOf(WindowStore.LIBRARY).of(Workspace.FLOATING))
        assertEquals(listOf(WindowStore.LIBRARY, WindowStore.PLAYLIST), memory.orderOf(Workspace.FLOATING))
    }

    @Test
    fun `a window moved back to its full size forgets the size and keeps the place`() {
        val store = WindowStore(app)
        store.save(
            mapOf(WindowStore.PLAYLIST to WindowPlacement(mapOf(Workspace.FLOATING to WindowArrangement(1, 2, 6)))).asMemory(),
        )

        store.save(
            mapOf(WindowStore.PLAYLIST to WindowPlacement(mapOf(Workspace.FLOATING to WindowArrangement(1, 2, null)))).asMemory(),
        )

        assertNull(
            WindowStore(app)
                .load()
                .placementOf(WindowStore.PLAYLIST)
                .of(Workspace.FLOATING)
                .size,
        )
        assertEquals(
            1,
            WindowStore(app)
                .load()
                .placementOf(WindowStore.PLAYLIST)
                .of(Workspace.FLOATING)
                .x,
        )
    }

    @Test
    fun `an order naming windows we do not have ignores them`() {
        WindowStore(app).save(
            WindowLayoutMemory(orders = mapOf(Workspace.FLOATING to listOf(WindowStore.SKINS, "video", WindowStore.PLAYLIST))),
        )

        assertEquals(listOf(WindowStore.SKINS, WindowStore.PLAYLIST), WindowStore(app).load().orderOf(Workspace.FLOATING))
    }

    @Test
    fun `a layout written by a newer build is not read`() {
        WindowStore(app).save(
            mapOf(WindowStore.PLAYLIST to WindowPlacement(mapOf(Workspace.FLOATING to WindowArrangement(9, 9, 9)))).asMemory(),
        )
        app
            .getSharedPreferences("windows", 0)
            .edit()
            .putInt("schema", 99)
            .commit()

        val memory = WindowStore(app).load()

        assertNull(memory.placementOf(WindowStore.PLAYLIST).of(Workspace.FLOATING).x)
    }

    private fun Map<String, WindowPlacement>.asMemory() = WindowLayoutMemory(this)

    @Test
    fun `the sideways arrangement is kept apart from the upright one`() {
        val placement =
            WindowPlacement(
                mapOf(
                    Workspace.FLOATING to WindowArrangement(x = 1, y = 2, size = 6, shaded = false, cols = 0, open = true),
                    Workspace.FLOATING_SIDEWAYS to
                        WindowArrangement(x = -300, y = 0, size = null, shaded = true, cols = 2, open = true),
                ),
            )
        WindowStore(app).save(mapOf(WindowStore.PLAYLIST to placement).asMemory())

        val loaded = WindowStore(app).load().placementOf(WindowStore.PLAYLIST)
        assertEquals(placement.of(Workspace.FLOATING), loaded.of(Workspace.FLOATING))
        assertEquals(placement.of(Workspace.FLOATING_SIDEWAYS), loaded.of(Workspace.FLOATING_SIDEWAYS))
        assertEquals("nothing was said about the locked workspaces", WindowArrangement(), loaded.of(Workspace.LOCKED))
    }

    @Test
    @Config(sdk = [35], qualifiers = "sw600dp")
    fun `a layout saved on a tablet before there were two arrangements is read for both ways round`() {
        app
            .getSharedPreferences("windows", 0)
            .edit()
            .putInt("schema", 1)
            .putInt("pl.x", 4)
            .putInt("pl.y", -20)
            .putInt("pl.size", 5)
            .commit()

        val playlist = WindowStore(app).load().placementOf(WindowStore.PLAYLIST)

        assertEquals(WindowArrangement(x = 4, y = -20, size = 5), playlist.of(Workspace.FLOATING))
        assertEquals(
            "a tablet turned before, so what it had is kept either way",
            playlist.of(Workspace.FLOATING),
            playlist.of(Workspace.FLOATING_SIDEWAYS),
        )
    }

    @Test
    fun `a layout saved before the phone could turn is the upright one`() {
        app
            .getSharedPreferences("windows", 0)
            .edit()
            .putInt("schema", 1)
            .putInt("pl.x", 4)
            .putInt("pl.y", -20)
            .putInt("pl.size", 5)
            .putInt("pl.open", 1)
            .commit()

        val playlist = WindowStore(app).load().placementOf(WindowStore.PLAYLIST)

        assertEquals(WindowArrangement(x = 4, y = -20, size = 5, open = true), playlist.of(Workspace.FLOATING))
        assertEquals(
            "sideways: only whether it was open",
            WindowArrangement(open = true),
            playlist.of(Workspace.FLOATING_SIDEWAYS),
        )
        assertEquals(
            "locked: only whether it was open",
            WindowArrangement(open = true, shaded = false),
            playlist.of(Workspace.LOCKED),
        )
    }

    @Test
    fun `whether a window is open survives a round trip`() {
        val store = WindowStore(app)

        store.save(
            WindowLayoutMemory(
                placements =
                    mapOf(WindowStore.MILKDROP to WindowPlacement(mapOf(Workspace.FLOATING to WindowArrangement(open = true)))),
                orders = mapOf(Workspace.FLOATING to emptyList()),
            ),
        )

        assertEquals(
            true,
            WindowStore(app)
                .load()
                .placementOf(WindowStore.MILKDROP)
                .of(Workspace.FLOATING)
                .open,
        )
    }

    @Test
    fun `a window nobody has ever opened has no answer either way`() {
        val store = WindowStore(app)
        store.save(WindowLayoutMemory())

        assertEquals(
            null,
            WindowStore(app)
                .load()
                .placementOf(WindowStore.MILKDROP)
                .of(Workspace.FLOATING)
                .open,
        )
    }

    @Test
    fun `a window that was closed comes back closed`() {
        val store = WindowStore(app)

        store.save(
            WindowLayoutMemory(
                placements =
                    mapOf(WindowStore.MILKDROP to WindowPlacement(mapOf(Workspace.FLOATING to WindowArrangement(open = false)))),
            ),
        )

        assertEquals(
            false,
            WindowStore(app)
                .load()
                .placementOf(WindowStore.MILKDROP)
                .of(Workspace.FLOATING)
                .open,
        )
    }

    @Test
    fun `a collapsed window is remembered collapsed`() {
        WindowStore(app).save(
            WindowLayoutMemory(
                placements =
                    mapOf(WindowStore.MAIN to WindowPlacement(mapOf(Workspace.FLOATING to WindowArrangement(shaded = true)))),
            ),
        )

        assertEquals(
            true,
            WindowStore(app)
                .load()
                .placementOf(WindowStore.MAIN)
                .of(Workspace.FLOATING)
                .shaded,
        )
    }

    @Test
    fun `a window nobody has ever collapsed has no answer either way`() {
        WindowStore(app).save(WindowLayoutMemory())

        assertEquals(
            null,
            WindowStore(app)
                .load()
                .placementOf(WindowStore.MAIN)
                .of(Workspace.FLOATING)
                .shaded,
        )
    }
}
