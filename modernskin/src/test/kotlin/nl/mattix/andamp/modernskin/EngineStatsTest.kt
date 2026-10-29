// SPDX-License-Identifier: GPL-3.0-or-later
package nl.mattix.andamp.modernskin

import nl.mattix.andamp.modernskin.skin.EngineStats
import nl.mattix.andamp.modernskin.skin.FakePlayer
import nl.mattix.andamp.modernskin.skin.SkinFiles
import nl.mattix.andamp.modernskin.skin.SkinRuntime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The engine's counters come out as one value ([SkinRuntime.stats]), and its color themes go by
 * the names the host reads them by ([SkinRuntime.colorThemes], [SkinRuntime.colorTheme],
 * [SkinRuntime.selectColorTheme]).
 */
class EngineStatsTest {
    private val files =
        object : SkinFiles {
            private val m =
                mapOf(
                    "skin.xml" to
                        """<WinampAbstractionLayer version="0.8"><include file="player.xml"/></WinampAbstractionLayer>""",
                    "player.xml" to
                        """<gammaset id="One"/><gammaset id="Two"/>""" +
                        """<container id="main" name="t" default_visible="1"><layout id="normal" w="200" h="100">""" +
                        """<group id="body" x="0" y="0" w="200" h="100" sysregion="1" rectrgn="1"/>""" +
                        """</layout></container>""",
                ).mapValues { it.value.toByteArray() }

            override fun read(path: String): ByteArray? = m[SkinFiles.normalize(path)]

            override fun names(): List<String> = m.keys.toList()
        }

    private val rt = SkinRuntime(files, TestHost(files), FakePlayer(), clock = { 0L })

    @Test
    fun `the counters start at nothing`() {
        assertEquals(EngineStats(), rt.stats)
    }

    @Test
    fun `the counters are the engine's own, read at once`() {
        rt.load()
        rt.tick()
        rt.drawWindows()
        val s = rt.stats
        assertEquals(rt.scriptSteps, s.scriptSteps)
        assertEquals(rt.timerFires, s.timerFires)
        assertEquals(rt.layoutPasses, s.layoutPasses)
        assertEquals(rt.shapesWorkedOut, s.shapesWorkedOut)
        assertTrue("a loaded skin was laid out", s.layoutPasses > 0)
    }

    @Test
    fun `color themes are listed, worn and picked by name`() {
        assertNull(rt.colorTheme)
        rt.load()
        assertEquals(listOf("One", "Two"), rt.colorThemes)

        rt.selectColorTheme("two")
        assertEquals("Two", rt.colorTheme)
        rt.selectColorTheme("none such")
        assertEquals("an unknown name changes nothing", "Two", rt.colorTheme)
    }
}
