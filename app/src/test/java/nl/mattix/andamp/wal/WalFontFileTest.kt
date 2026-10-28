// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.wal

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/**
 * Where a modern skin's font is kept for Android to read: a file in the cache, flushed to the disk
 * before the rename that names it, and written again when the file there is empty or short, as a
 * phone that switched off mid-write can leave it.
 */
class WalFontFileTest {
    @get:Rule val folder = TemporaryFolder()

    private val cache get() = folder.root

    private val font = ByteArray(300) { it.toByte() }

    @Test
    fun `a font is flushed whole before the rename names it`() {
        val seen = ArrayList<String>()

        val file =
            WalHost.fontFile(cache, font) { channel ->
                val stored = cache.listFiles()!!.filter { it.name.endsWith(".ttf") }
                val part = cache.listFiles()!!.single { it.name.endsWith(".tmp") }
                seen += "part ${part.length()}, stored ${stored.size}"
                channel.force(true)
            }

        assertEquals(listOf("part 300, stored 0"), seen)
        assertArrayEquals(font, file.readBytes())
        assertEquals("no part is left", listOf(file.name), cache.list()!!.toList())
    }

    @Test
    fun `an empty font file left by a cut is written again`() {
        val name = WalHost.fontFile(cache, font).name
        File(cache, name).writeBytes(ByteArray(0))

        val file = WalHost.fontFile(cache, font)

        assertArrayEquals(font, file.readBytes())
    }

    @Test
    fun `a font file cut short is written again`() {
        val name = WalHost.fontFile(cache, font).name
        File(cache, name).writeBytes(font.copyOf(100))

        val file = WalHost.fontFile(cache, font)

        assertArrayEquals(font, file.readBytes())
    }

    @Test
    fun `a whole font file is used as it is, without writing it again`() {
        WalHost.fontFile(cache, font)
        var flushes = 0

        WalHost.fontFile(cache, font) {
            flushes++
            it.force(true)
        }

        assertEquals(0, flushes)
    }

    @Test
    fun `a font whose flush fails leaves no file and no part`() {
        val file = WalHost.fontFile(cache, font) { throw IOException("the disk failed") }

        assertFalse(file.exists())
        assertEquals(emptyList<String>(), cache.list()!!.toList())
    }
}
