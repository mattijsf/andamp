// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state.online

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The museum's search answers `"Unexpected error."` for a query holding punctuation such as the
 * dot of a filename: `zelda.wsz` fails, `zelda` does not. [MuseumQuery.sanitise] turns that
 * punctuation into spaces.
 */
class MuseumQueryTest {
    @Test
    fun `a filename is asked for as words`() {
        assertEquals("zelda wsz", MuseumQuery.sanitise("zelda.wsz"))
    }

    @Test
    fun `every mark the endpoint errors on becomes a space`() {
        // the digit stays attached to the word before the dot
        assertEquals("winamp2 8", MuseumQuery.sanitise("winamp2.8"))
        assertEquals("zelda amp", MuseumQuery.sanitise("zelda-amp"))
        assertEquals("zelda amp", MuseumQuery.sanitise("zelda:amp"))
        assertEquals("zelda amp", MuseumQuery.sanitise("zelda&amp"))
        assertEquals("zelda amp", MuseumQuery.sanitise("zelda(amp)"))
        assertEquals("zelda amp", MuseumQuery.sanitise("zelda'amp"))
        assertEquals("what", MuseumQuery.sanitise("what?"))
        assertEquals("hey", MuseumQuery.sanitise("hey!"))
    }

    @Test
    fun `what the endpoint accepts is kept`() {
        assertEquals("zelda_amp", MuseumQuery.sanitise("zelda_amp"))
        assertEquals("zelda+amp", MuseumQuery.sanitise("zelda+amp"))
        assertEquals("zelda*", MuseumQuery.sanitise("zelda*"))
        assertEquals("3 0", MuseumQuery.sanitise("3 0"))
    }

    @Test
    fun `letters of any alphabet survive`() {
        assertEquals("pokémon", MuseumQuery.sanitise("pokémon"))
        assertEquals("初音", MuseumQuery.sanitise("初音"))
    }

    @Test
    fun `runs of punctuation and spaces collapse to one space`() {
        assertEquals("a b", MuseumQuery.sanitise("a...b"))
        assertEquals("a b", MuseumQuery.sanitise("  a  --  b  "))
    }

    @Test
    fun `a query of nothing but punctuation or spaces becomes empty`() {
        assertEquals("", MuseumQuery.sanitise("..."))
        assertEquals("", MuseumQuery.sanitise("   "))
    }
}
