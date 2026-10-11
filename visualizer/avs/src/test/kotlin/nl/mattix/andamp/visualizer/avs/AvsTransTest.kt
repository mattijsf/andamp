// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.visualizer.avs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [AvsTrans]: what the translator makes of a preset's code. Spaces and line ends are not compared. */
class AvsTransTest {
    private fun translated(
        code: String,
        prefix: String = "",
        mode: AvsTrans.Mode = AvsTrans.Mode.LINEAR,
        topLevel: Boolean = false,
    ) = AvsTrans(mode, topLevel).translate(prefix, code).filterNot { it.isWhitespace() }

    @Test
    fun `plain code comes out as its statements`() {
        assertEquals("x=1;y=x*2;", translated("x=1;\r\ny=x*2"))
    }

    @Test
    fun `comments are taken out, and a division is not one`() {
        assertEquals("x=a/b;y=2;", translated("x=a/b; // half\r\n/* gone */ y=2;"))
    }

    @Test
    fun `a define is a word replaced wherever it appears, whatever its case`() {
        assertEquals("x=3.14*2;", translated("#define PI 3.14\r\nx=pi*2;"))
    }

    @Test
    fun `a define with parameters is a macro`() {
        assertEquals("x=(a*a);y=(1+2*1+2);", translated("#define sq(v) (v*v)\r\nx=sq(a);y=sq(1+2);"))
    }

    @Test
    fun `a macro call with the wrong number of arguments is left as it is`() {
        assertEquals("x=sq(a,b);", translated("#define sq(v) (v*v)\r\nx=sq(a,b);"))
    }

    @Test
    fun `a macro pastes its parameter to a name with two hashes`() {
        assertEquals("x=reg01;", translated("#define r(n) reg##n\r\nx=r(01);"))
    }

    @Test
    fun `macros defined in the AVSTrans component's own code are known in every piece of code`() {
        assertEquals("x=(4+1);", translated("x=inc(4);", prefix = "#define inc(v) (v+1)"))
    }

    @Test
    fun `in assign form an assignment inside a call becomes assign`() {
        assertEquals(
            "if(a,assign(x,1),assign(y,2));",
            translated("if(a,x=1,y=2);", mode = AvsTrans.Mode.ASSIGN),
        )
    }

    @Test
    fun `in exec form several statements inside a call are folded into exec2 and exec3`() {
        assertEquals(
            "if(a,exec2(assign(x,1),assign(y,2)),exec3(assign(p,1),assign(q,2),assign(r,3)))",
            translated("if(a,x=1;y=2,p=1;q=2;r=3);", mode = AvsTrans.Mode.EXEC),
        )
    }

    @Test
    fun `a chained assignment is nested`() {
        assertEquals("if(a,assign(x,assign(y,1)),0);", translated("if(a,x=y=1,0);", mode = AvsTrans.Mode.ASSIGN))
    }

    @Test
    fun `the form can be chosen by a comment in the code`() {
        assertEquals("if(a,assign(x,1),0);", translated("//\$avstrans_mode=assign\r\nif(a,x=1,0);"))
    }

    @Test
    fun `the top level is rewritten only when the preset asks`() {
        assertEquals("x=1;", translated("x=1;", mode = AvsTrans.Mode.ASSIGN))
        assertEquals("assign(x,1);", translated("x=1;", mode = AvsTrans.Mode.ASSIGN, topLevel = true))
    }

    @Test
    fun `a replacement can be named in a comment`() {
        assertEquals("x=sin(t);", translated("//\$avstrans_replacement=wave->sin\r\nx=wave(t);"))
    }

    @Test
    fun `brackets that do not close are marked and not guessed at`() {
        assertTrue("BRACKETS" in translated("x=sin(t;"))
    }

    @Test
    fun `an include has no file to read and is dropped`() {
        assertEquals("x=1;", translated("#include lib.txt\r\nx=1;"))
    }

    @Test
    fun `code with unix line ends is read the same`() {
        assertEquals("x=3.14;", translated("#define PI 3.14\nx=PI; // done\n"))
    }

    @Test
    fun `a preset with no AVSTrans switched on translates nothing`() {
        assertNull(AvsTrans.settingsOf(emptyList()))
        assertNull(AvsTrans.settingsOf(listOf(int32(0) + int32(0) + int32(0) + int32(0) + "#define A 1\u0000".toByteArray())))
    }

    @Test
    fun `the components' code is joined, and code marked notrans is left alone`() {
        val one = int32(1) + int32(0) + int32(0) + int32(1) + "#define A 1\u0000".toByteArray()
        val two = int32(1) + int32(0) + int32(0) + int32(1) + "#define B 2\u0000".toByteArray()
        val translate = AvsTrans.translatorFor(AvsTrans.settingsOf(listOf(one, two))!!)

        assertEquals("x=1+2;", translate("x=A+B;").filterNot { it.isWhitespace() })
        assertEquals("//\$notrans\r\nx=A;", translate("//\$notrans\r\nx=A;"))
    }

    @Test
    fun `while a preset with AVSTrans loads, every piece of code goes through it`() {
        val one = int32(1) + int32(0) + int32(0) + int32(1) + "#define TWO 2\u0000".toByteArray()
        MovementEffect.translator.set(AvsTrans.translatorFor(AvsTrans.settingsOf(listOf(one))!!))
        try {
            assertEquals("x=2", MovementEffect.asEel("x=TWO;").filterNot { it.isWhitespace() })
        } finally {
            MovementEffect.translator.remove()
        }
        assertEquals("x=TWO", MovementEffect.asEel("x=TWO;"))
    }

    private fun int32(value: Int) =
        byteArrayOf(value.toByte(), (value shr 8).toByte(), (value shr 16).toByte(), (value shr 24).toByte())
}
