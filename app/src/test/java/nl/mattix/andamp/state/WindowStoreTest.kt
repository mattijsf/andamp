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
        assertNull(playlist.x)
        assertNull(playlist.y)
        assertNull(playlist.size)
        assertEquals(emptyList<String>(), memory.order)
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
                        WindowStore.PLAYLIST to WindowPlacement(x = -12, y = 40, size = 6),
                        WindowStore.LIBRARY to WindowPlacement(x = 0, y = -30, size = null),
                    ),
                order = listOf(WindowStore.LIBRARY, WindowStore.PLAYLIST),
            ),
        )

        val memory = WindowStore(app).load()

        assertEquals(WindowPlacement(-12, 40, 6), memory.placementOf(WindowStore.PLAYLIST))
        assertEquals(WindowPlacement(0, -30, null), memory.placementOf(WindowStore.LIBRARY))
        assertEquals(listOf(WindowStore.LIBRARY, WindowStore.PLAYLIST), memory.order)
    }

    @Test
    fun `a window moved back to its full size forgets the size and keeps the place`() {
        val store = WindowStore(app)
        store.save(mapOf(WindowStore.PLAYLIST to WindowPlacement(1, 2, 6)).asMemory())

        store.save(mapOf(WindowStore.PLAYLIST to WindowPlacement(1, 2, null)).asMemory())

        assertNull(WindowStore(app).load().placementOf(WindowStore.PLAYLIST).size)
        assertEquals(1, WindowStore(app).load().placementOf(WindowStore.PLAYLIST).x)
    }

    @Test
    fun `an order naming windows we do not have ignores them`() {
        WindowStore(app).save(
            WindowLayoutMemory(order = listOf(WindowStore.SKINS, "video", WindowStore.PLAYLIST)),
        )

        assertEquals(listOf(WindowStore.SKINS, WindowStore.PLAYLIST), WindowStore(app).load().order)
    }

    @Test
    fun `a layout written by a newer build is not read`() {
        WindowStore(app).save(mapOf(WindowStore.PLAYLIST to WindowPlacement(9, 9, 9)).asMemory())
        app
            .getSharedPreferences("windows", 0)
            .edit()
            .putInt("schema", 99)
            .commit()

        val memory = WindowStore(app).load()

        assertNull(memory.placementOf(WindowStore.PLAYLIST).x)
    }

    private fun Map<String, WindowPlacement>.asMemory() = WindowLayoutMemory(this, emptyList())

    @Test
    fun `whether a window is open survives a round trip`() {
        val store = WindowStore(app)

        store.save(
            WindowLayoutMemory(
                placements = mapOf(WindowStore.MILKDROP to WindowPlacement(open = true)),
                order = emptyList(),
            ),
        )

        assertEquals(true, WindowStore(app).load().placementOf(WindowStore.MILKDROP).open)
    }

    @Test
    fun `a window nobody has ever opened has no answer either way`() {
        val store = WindowStore(app)
        store.save(WindowLayoutMemory())

        assertEquals(null, WindowStore(app).load().placementOf(WindowStore.MILKDROP).open)
    }

    @Test
    fun `a window that was closed comes back closed`() {
        val store = WindowStore(app)

        store.save(
            WindowLayoutMemory(placements = mapOf(WindowStore.MILKDROP to WindowPlacement(open = false))),
        )

        assertEquals(false, WindowStore(app).load().placementOf(WindowStore.MILKDROP).open)
    }

    @Test
    fun `a collapsed window is remembered collapsed`() {
        WindowStore(app).save(
            WindowLayoutMemory(placements = mapOf(WindowStore.MAIN to WindowPlacement(shaded = true))),
        )

        assertEquals(true, WindowStore(app).load().placementOf(WindowStore.MAIN).shaded)
    }

    @Test
    fun `a window nobody has ever collapsed has no answer either way`() {
        WindowStore(app).save(WindowLayoutMemory())

        assertEquals(null, WindowStore(app).load().placementOf(WindowStore.MAIN).shaded)
    }
}
