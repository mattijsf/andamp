// SPDX-License-Identifier: GPL-3.0-or-later
package nl.mattix.andamp.modernskin

import nl.mattix.andamp.modernskin.skin.FakePlayer
import nl.mattix.andamp.modernskin.skin.FontDef
import nl.mattix.andamp.modernskin.skin.Presentation
import nl.mattix.andamp.modernskin.skin.SkinFiles
import nl.mattix.andamp.modernskin.skin.SkinHost
import nl.mattix.andamp.modernskin.skin.SkinRuntime
import nl.mattix.andamp.modernskin.skin.SkinSetup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the host gives a skin as values: [SkinSetup] before it loads, the clock with it, and
 * [Presentation] for how its windows show. Button actions reach the host's hostAction when the
 * setup says the host answers them.
 */
class PresentationTest {
    private val files =
        object : SkinFiles {
            private val m =
                mapOf(
                    "skin.xml" to
                        """<WinampAbstractionLayer version="0.8"><include file="player.xml"/></WinampAbstractionLayer>""",
                    "player.xml" to
                        """<container id="main" name="t" default_visible="1"><layout id="normal" w="300" h="40">""" +
                        """<button id="min" action="MINIMIZE" x="0" y="0" w="20" h="20"/>""" +
                        """</layout></container>""",
                ).mapValues { it.value.toByteArray() }

            override fun read(path: String): ByteArray? = m[SkinFiles.normalize(path)]

            override fun names(): List<String> = m.keys.toList()
        }

    private val asked = ArrayList<Pair<String, String?>>()

    private val host =
        object : SkinHost {
            override fun imageSize(file: String): Pair<Int, Int>? = null

            override fun textWidth(
                text: String,
                font: FontDef?,
                size: Int,
            ) = text.length * 6

            override fun log(message: String) = Unit

            override fun invalidate() = Unit

            override fun hostAction(
                action: String,
                param: String?,
            ) {
                asked += action to param
            }
        }

    private fun runtime(setup: SkinSetup = SkinSetup()) =
        SkinRuntime(files, host, FakePlayer(), setup, clock = { 1_000L }).apply {
            load()
            presentation = presentation.copy(viewSize = 300 to 600)
        }

    private fun SkinRuntime.tapMinimize() {
        ensureLayout()
        pointerDown(5, 5)
        pointerUp(5, 5)
    }

    @Test
    fun `the setup names the skin and says whether the library is inside it`() {
        val rt = runtime(SkinSetup(skinName = "Bento", haveLibrary = false))
        assertEquals("Bento", rt.skinName)
        assertFalse(rt.haveLibrary)

        val plain = runtime()
        assertEquals("", plain.skinName)
        assertTrue(plain.haveLibrary)
    }

    @Test
    fun `the clock given with the setup is the one the skin reads`() {
        assertEquals(1_000L, runtime().now())
    }

    @Test
    fun `button actions reach the host only when the setup says it answers them`() {
        runtime(SkinSetup(hostActions = true)).tapMinimize()
        assertEquals(listOf("MINIMIZE" to null), asked)

        asked.clear()
        runtime().tapMinimize()
        assertEquals(emptyList<Pair<String, String?>>(), asked)
    }

    @Test
    fun `the presentation reads and sets the window settings as one value`() {
        val rt = SkinRuntime(files, host, FakePlayer())
        assertEquals(Presentation(), rt.presentation)

        val p =
            Presentation(
                viewSize = 200 to 400,
                viewport = 800 to 600,
                columns = 2,
                columnWidth = 150,
                stretchLast = false,
                touchSlop = 9,
            )
        rt.presentation = p
        assertEquals(p, rt.presentation)
        assertEquals(200 to 400, rt.viewSize)
        assertEquals(800 to 600, rt.viewportW to rt.viewportH)
        assertEquals(listOf(2, 150, 9), listOf(rt.columns, rt.columnWidth, rt.touchSlop))
        assertFalse(rt.stretchLast)

        rt.presentation = rt.presentation.copy(viewSize = null)
        assertNull(rt.viewSize)
        assertEquals("the screen scripts see before there is a view", 800 to 600, rt.viewport)
    }

    @Test
    fun `setting the same presentation again lays nothing out again`() {
        val rt = runtime()
        rt.ensureLayout()
        val before = rt.changeCount
        rt.presentation = rt.presentation.copy()
        assertEquals(before, rt.changeCount)
        rt.presentation = rt.presentation.copy(columns = 2)
        assertTrue(rt.changeCount > before)
    }

    @Test
    fun `a tick can place the next slide ahead of now`() {
        val rt = runtime()
        rt.tick(slideLead = 12)
        assertEquals(12L, rt.slideLead)
        rt.tick(slideLead = 0)
        assertEquals(0L, rt.slideLead)
    }
}
