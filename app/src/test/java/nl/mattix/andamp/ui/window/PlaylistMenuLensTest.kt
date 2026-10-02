// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.window

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Which of the playlist's bottom-bar controls are worth a magnifier.
 *
 * None of the buttons, all of what they open. The lens is for picking one control out of
 * its neighbors, and a hold on ADD, REM, SEL, MISC or LIST OPTS is a hold on the thing
 * that opens the stack. The stacks are tiles that differ only in a sprite, and every one
 * of them keeps the lens.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlaylistMenuLensTest {
    private val widgets = playlistWidgets(testViewModel(), PlaylistLayout.ofSegments(4))

    private fun widget(id: String) = widgets.first { it.id == id }

    @Test
    fun `no menu button asks for the lens`() {
        listOf("pl.menu.add", "pl.menu.rem", "pl.menu.sel", "pl.menu.misc", "pl.menu.list").forEach {
            assertFalse("$it gets no lens", Loupe.fiddly(widget(it)))
        }
    }

    /** What they open is a stack of neighbors, whichever button opened it. */
    @Test
    fun `every menu entry asks for it`() {
        listOf("pl.menu.list.new", "pl.menu.add.file", "pl.menu.rem.all", "pl.menu.sel.all").forEach {
            assertTrue("$it gets the lens", Loupe.fiddly(widget(it)))
        }
    }
}
