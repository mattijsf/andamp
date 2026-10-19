// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The marks a source's rows carry, and what is accepted as one. */
class SourceMarkTest {
    @Test
    fun `the phone has pixel art and a path of its own`() {
        val phone = markOf(MusicSource.LOCAL, EnglishWords)

        assertEquals(SourceMark.PHONE, phone)
        assertTrue(phone.pixelWidth in 1..SourceMark.MAX_W)
        assertTrue(phone.pixels.size <= SourceMark.MAX_H)
        assertTrue(phone.ink(0, 0))
        assertFalse(phone.ink(1, 1))
    }

    @Test
    fun `a source that gives no picture is marked by the first letter of its name`() {
        assertEquals("E", markOf(MusicSource("EXAMPLE", "example music"), EnglishWords).letter)
        assertEquals("9", SourceMark.letter("9 Lives").letter)
        assertEquals(0, SourceMark.letter("Example").pixelWidth)
    }

    @Test
    fun `pixel art too large or with other signs is not taken`() {
        assertEquals(listOf("#.#", ".#."), SourceMark.pixelsOrEmpty(listOf("#.#", ".#.")))
        assertEquals(emptyList<String>(), SourceMark.pixelsOrEmpty(listOf("##########")))
        assertEquals(emptyList<String>(), SourceMark.pixelsOrEmpty(List(10) { "#" }))
        assertEquals(emptyList<String>(), SourceMark.pixelsOrEmpty(listOf("#x#")))
    }
}
