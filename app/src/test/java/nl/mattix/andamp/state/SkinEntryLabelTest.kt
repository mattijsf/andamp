// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import org.junit.Assert.assertEquals
import org.junit.Test

/** A modern skin's row says it is one, in the Skins menu and the Skin Browser alike. */
class SkinEntryLabelTest {
    @Test
    fun `a modern skin's row says so after its name`() {
        assertEquals("Bento · Modern skin", SkinEntry("bento", "Bento", 0, SkinKind.MODERN).label(EnglishWords))
    }

    @Test
    fun `a classic skin's row is its name`() {
        assertEquals("Bento.wsz", SkinEntry("bento", "Bento.wsz", 0).label(EnglishWords))
    }
}
