// SPDX-License-Identifier: GPL-3.0-or-later
package nl.mattix.andamp.modernskin

import nl.mattix.andamp.modernskin.skin.WalArchive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A zip is a modern skin when it holds a skin.xml, at the root or in a folder. */
class WalArchiveTest {
    @Test
    fun `skin xml at the root`() {
        assertEquals("", WalArchive.skinFolder(listOf("player.xml", "skin.xml")))
    }

    @Test
    fun `skin xml in a folder, whatever the case and slashes`() {
        assertEquals("bento/", WalArchive.skinFolder(listOf("Bento\\", "Bento\\Skin.XML", "Bento\\player.xml")))
        assertEquals("a/b/", WalArchive.skinFolder(listOf("a/b/skin.xml")))
    }

    @Test
    fun `the root wins over a folder, and the first folder over later ones`() {
        assertEquals("", WalArchive.skinFolder(listOf("x/skin.xml", "skin.xml")))
        assertEquals("x/", WalArchive.skinFolder(listOf("x/skin.xml", "y/skin.xml")))
    }

    @Test
    fun `a classic skin has none`() {
        val classic = listOf("main.bmp", "cbuttons.bmp", "pledit.txt", "skin.xml.bak", "myskin.xml", "docs/winampskin.xml")
        assertNull(WalArchive.skinFolder(classic))
        assertFalse(WalArchive.isModern(classic))
        assertTrue(WalArchive.isModern(listOf("skin.xml")))
    }
}
