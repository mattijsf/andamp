// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The playlist's font and the height of a list's rows, as the listener left them. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ListStyleStoreTest {
    private val app: Context = ApplicationProvider.getApplicationContext()

    private fun prefs(): SharedPreferences = app.getSharedPreferences("liststyle-test-${System.nanoTime()}", Context.MODE_PRIVATE)

    @Test
    fun `the playlist is in the skin's font, in rows as tall as Winamp's, until the listener picks otherwise`() {
        val store = ListStyleStore(prefs())

        assertEquals(ListFont.SKIN, store.font)
        assertEquals(RowHeight.CLASSIC, store.rowHeight)
        assertEquals(13, store.rowHeight.px)
    }

    @Test
    fun `what the listener picked is what comes back`() {
        val prefs = prefs()
        ListStyleStore(prefs).apply {
            font = ListFont.REGULAR
            rowHeight = RowHeight.LARGE
        }

        // a second store over the same file is the next launch
        val next = ListStyleStore(prefs)
        assertEquals(ListFont.REGULAR, next.font)
        assertEquals(RowHeight.LARGE, next.rowHeight)
    }

    @Test
    fun `a stored choice this version does not have reads as the default`() {
        val prefs = prefs()
        prefs
            .edit()
            .putString("font", "COMIC")
            .putString("rowheight", "HUGE")
            .commit()

        val store = ListStyleStore(prefs)

        assertEquals(ListFont.SKIN, store.font)
        assertEquals(RowHeight.CLASSIC, store.rowHeight)
    }

    @Test
    fun `every row height is odd, so a letter five pixels tall sits in its middle`() {
        assertEquals(emptyList<RowHeight>(), RowHeight.entries.filter { it.px % 2 == 0 })
        assertEquals(RowHeight.entries.sortedBy { it.px }, RowHeight.entries)
    }
}
