// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.window

import org.junit.Assert.assertEquals
import org.junit.Test

class GenWindowTest {
    @Test
    fun `chrome matches webamp's gen window`() {
        // webamp: CHROME_WIDTH 19, CHROME_HEIGHT 34
        assertEquals(19, GenWindow.CHROME_W)
        assertEquals(34, GenWindow.CHROME_H)
    }

    @Test
    fun `content area is the window minus the frame`() {
        assertEquals(275 - 19, GenWindow.contentWidth(275))
        assertEquals(154 - 34, GenWindow.contentHeight(154))
    }

    @Test
    fun `close button sits 2px in from the right edge`() {
        assertEquals(275 - 2 - 9, GenWindow.closeX(275))
    }

    @Test
    fun `the milkdrop window reserves room for its content whatever the chrome`() {
        assertEquals(256, GenWindow.contentWidth(MILKDROP_W))
        // the window grows to fit the visual, rather than the visual shrinking
        assertEquals(MILKDROP_CONTENT_H, milkdropHeight(GenFrame) - GenFrame.chromeH)
        assertEquals(MILKDROP_CONTENT_H, milkdropHeight(PleditWindowFrame) - PleditWindowFrame.chromeH)
    }
}
