// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.visualizer.avs

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * AVS's statement style into projectm-eval's: commas to semicolons outside
 * brackets, and no empty statements, which projectm-eval rejects.
 */
class MovementEffectsTest {
    @Test
    fun `commas outside brackets become semicolons, inside they are arguments`() {
        assertEquals("x=x+1;y=atan2(y, x)", MovementEffect.asEel("x=x+1, y=atan2(y, x),"))
    }

    /** Guards against a section that opens with `;` failing to compile. */
    @Test
    fun `a leading separator is dropped`() {
        assertEquals("ang=ang+1;rtx=rtx+2", MovementEffect.asEel(";ang=ang+1;rtx=rtx+2"))
    }

    @Test
    fun `doubled and trailing separators collapse`() {
        assertEquals("a=1;b=2", MovementEffect.asEel("a=1;;b=2;"))
        assertEquals("a=1;b=2", MovementEffect.asEel("a=1,,b=2,,"))
    }

    @Test
    fun `blank code stays blank`() {
        assertEquals("", MovementEffect.asEel("  ;; , "))
    }

    /** A comma inside a comment is not a separator, and the comment must not swallow the next line. */
    @Test
    fun `line comments are stripped before the separators are read`() {
        assertEquals("x=x+1;y=2", MovementEffect.asEel("x=x+1, // shift, then\ny=2,"))
        assertEquals("x=1", MovementEffect.asEel("x=1 // all of this goes, even the comma"))
    }

    /** The string from `e_movement.h`, with its -(1/sw) term. */
    @Test
    fun `shift rotate left carries the header's screen-width correction`() {
        assertEquals("x=x+(1/32)-(1/sw)", MovementEffects[2]!!.eel)
    }

    /**
     * The mesh hands scripts `r = atan2 + pi/2`, so the table carries the
     * header's compensated form of `r = cos(r*3)`.
     */
    @Test
    fun `cosine radial 3-way is the compensated form for the offset r`() {
        assertEquals(
            "r = cos((r - 3.141592653589793/2) * 3) + 3.141592653589793/2",
            MovementEffects[16]!!.eel,
        )
    }
}
