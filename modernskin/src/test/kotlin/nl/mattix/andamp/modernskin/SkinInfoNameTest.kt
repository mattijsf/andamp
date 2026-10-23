// SPDX-License-Identifier: GPL-3.0-or-later
package nl.mattix.andamp.modernskin

import nl.mattix.andamp.modernskin.skin.FakePlayer
import nl.mattix.andamp.modernskin.skin.FontDef
import nl.mattix.andamp.modernskin.skin.SkinFiles
import nl.mattix.andamp.modernskin.skin.SkinHost
import nl.mattix.andamp.modernskin.skin.SkinRuntime
import org.junit.Assert.assertEquals
import org.junit.Test

/** A skin names itself in `<skininfo><name>`, which the app falls back on when it has no name for it. */
class SkinInfoNameTest {
    private val files =
        object : SkinFiles {
            private val m =
                mapOf(
                    "skin.xml" to
                        """<WinampAbstractionLayer version="0.8">""" +
                        """<skininfo><version>1.0</version><name>Big Bento</name><author>Victor</author></skininfo>""" +
                        """<include file="player.xml"/></WinampAbstractionLayer>""",
                    "player.xml" to
                        """<container id="main" name="t" default_visible="1"><layout id="normal" w="200" h="100"/></container>""",
                ).mapValues { it.value.toByteArray() }

            override fun read(path: String): ByteArray? = m[SkinFiles.normalize(path)]

            override fun names(): List<String> = m.keys.toList()
        }

    private val host =
        object : SkinHost {
            override fun imageSize(file: String): Pair<Int, Int>? = null

            override fun textWidth(
                text: String,
                font: FontDef?,
                size: Int,
            ) = 0

            override fun log(message: String) = Unit

            override fun invalidate() = Unit
        }

    @Test
    fun `a skin the app has no name for takes the one in skininfo`() {
        val rt = SkinRuntime(files, host, FakePlayer())

        rt.load()

        assertEquals("Big Bento", rt.skinName)
    }

    @Test
    fun `a name the app gave stays`() {
        val rt = SkinRuntime(files, host, FakePlayer()).apply { skinName = "Bento" }

        rt.load()

        assertEquals("Bento", rt.skinName)
    }
}
