// SPDX-License-Identifier: GPL-3.0-or-later
package nl.mattix.andamp.modernskin

import nl.mattix.andamp.modernskin.skin.Button
import nl.mattix.andamp.modernskin.skin.DrawCmd
import nl.mattix.andamp.modernskin.skin.FakePlayer
import nl.mattix.andamp.modernskin.skin.FontDef
import nl.mattix.andamp.modernskin.skin.Group
import nl.mattix.andamp.modernskin.skin.SkinFiles
import nl.mattix.andamp.modernskin.skin.SkinHost
import nl.mattix.andamp.modernskin.skin.SkinRuntime
import nl.mattix.andamp.modernskin.skin.ZipSkinFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A button that opens and closes a window (a TOGGLE) lights while that window is open, however it
 * opened or closed: by the button, by another button, or by Back on the phone
 * ([SkinRuntime.closeTopWindow]). Winamp3x's playlist and equalizer lights are two-state buttons,
 * Winamp Modern's playlist button a plain one. A button for EQ on or EQ auto shows that switch as
 * it is, wherever it was changed.
 */
class ToggleLightTest {
    private val files =
        object : SkinFiles {
            private val m =
                mapOf(
                    "skin.xml" to
                        """<WinampAbstractionLayer version="0.8"><elements>""" +
                        """<bitmap id="light.0" file="l0.png"/><bitmap id="light.1" file="l1.png"/>""" +
                        """<bitmap id="eq.off" file="e0.png"/><bitmap id="eq.on" file="e1.png"/>""" +
                        """<bitmap id="pl.off" file="p0.png"/><bitmap id="pl.on" file="p1.png"/>""" +
                        """<bitmap id="sw.off" file="s0.png"/><bitmap id="sw.on" file="s1.png"/>""" +
                        """<bitmap id="eqlight.0" file="q0.png"/><bitmap id="eqlight.1" file="q1.png"/>""" +
                        """<bitmap id="auto.off" file="a0.png"/><bitmap id="auto.on" file="a1.png"/>""" +
                        """</elements><include file="player.xml"/></WinampAbstractionLayer>""",
                    "player.xml" to
                        """<container id="main" name="t" default_visible="1"><layout id="normal" w="200" h="100">""" +
                        """<nstatesbutton id="pl.light" action="TOGGLE" param="GUID:PL" nstates="2" cfgvals="0;1" """ +
                        """image="light." x="0" y="0" w="20" h="20"/>""" +
                        """<togglebutton id="eq.toggle" action="TOGGLE" param="eq" image="eq.off" activeimage="eq.on" """ +
                        """x="40" y="0" w="20" h="20"/>""" +
                        """<button id="pl.button" action="TOGGLE" param="guid:pl" image="pl.off" activeimage="pl.on" """ +
                        """x="80" y="0" w="20" h="20"/>""" +
                        """<togglebutton id="eq.switch" action="EQ_TOGGLE" image="sw.off" activeimage="sw.on" """ +
                        """x="120" y="0" w="20" h="20"/>""" +
                        """<nstatesbutton id="eq.light" action="EQ_TOGGLE" nstates="2" image="eqlight." """ +
                        """x="160" y="0" w="20" h="20"/>""" +
                        """<togglebutton id="auto.a" action="EQ_AUTO" image="auto.off" activeimage="auto.on" """ +
                        """x="0" y="40" w="20" h="20"/>""" +
                        """<togglebutton id="auto.b" action="EQ_AUTO" image="auto.off" activeimage="auto.on" """ +
                        """x="40" y="40" w="20" h="20"/>""" +
                        """</layout></container>""" +
                        """<container id="pl" name="Playlist" default_visible="1"><layout id="normal" w="200" h="80"/>""" +
                        """</container>""" +
                        """<container id="eq" name="Equalizer" default_visible="0"><layout id="normal" w="200" h="60"/>""" +
                        """</container>""",
                ).mapValues { it.value.toByteArray() }

            override fun read(path: String): ByteArray? = m[SkinFiles.normalize(path)]

            override fun names(): List<String> = m.keys.toList()
        }

    private val host =
        object : SkinHost {
            override fun imageSize(file: String): Pair<Int, Int> = 20 to 20

            override fun textWidth(
                text: String,
                font: FontDef?,
                size: Int,
            ) = text.length * 6

            override fun log(message: String) = Unit

            override fun invalidate() = Unit
        }

    private val player = FakePlayer()

    private val rt =
        SkinRuntime(files, host, player).apply {
            load()
            viewSize = 200 to 400
        }

    private fun button(id: String) = checkNotNull(rt.mainLayout?.findObject(id)) as Button

    /** The picture the frame drawn now shows for [b]. */
    private fun drawn(b: Button): String =
        rt
            .drawWindows()
            .first()
            .second
            .filterIsInstance<DrawCmd.Image>()
            .single { it.dst.left == b.rect.left && it.dst.top == b.rect.top }
            .bitmap.id

    @Test
    fun `a two-state light goes out when Back closes its window, and the next tap opens it again`() {
        val light = button("pl.light")
        assertEquals("light.0", drawn(light))

        light.click()
        assertSame(rt.container("pl"), rt.topWindow)
        assertEquals("light.1", drawn(light))

        rt.closeTopWindow()
        assertTrue("the frame after Back is asked for", rt.tick())
        assertNull(rt.topWindow)
        assertEquals("light.0", drawn(light))

        light.click()
        assertSame("the next tap opens the window", rt.container("pl"), rt.topWindow)
        assertEquals("and lights it", "light.1", drawn(light))
    }

    @Test
    fun `a two-state light follows its window opened and closed by another button`() {
        val light = button("pl.light")
        val other = button("pl.button")

        other.click()
        rt.tick()
        assertEquals("light.1", drawn(light))
        assertEquals("pl.on", drawn(other))

        other.click()
        rt.tick()
        assertEquals("light.0", drawn(light))
        assertEquals("pl.off", drawn(other))
    }

    @Test
    fun `a toggle button goes dark when Back closes its window`() {
        val toggle = button("eq.toggle")

        toggle.click()
        assertSame(rt.container("eq"), rt.topWindow)
        assertEquals("eq.on", drawn(toggle))

        rt.closeTopWindow()
        rt.tick()
        assertEquals("eq.off", drawn(toggle))

        toggle.click()
        assertSame("the next tap opens the window", rt.container("eq"), rt.topWindow)
        assertEquals("eq.on", drawn(toggle))
    }

    @Test
    fun `a plain button goes dark when Back closes its window`() {
        val plain = button("pl.button")

        plain.click()
        assertEquals("pl.on", drawn(plain))

        rt.closeTopWindow()
        rt.tick()
        assertEquals("pl.off", drawn(plain))
    }

    @Test
    fun `a toggle button for EQ on goes dark when its first tap turns the EQ off`() {
        val toggle = button("eq.switch")
        player.eqEnabled = true
        rt.tick()
        assertEquals("sw.on", drawn(toggle))

        toggle.click()
        assertFalse(player.eqEnabled)
        assertEquals("sw.off", drawn(toggle))

        toggle.click()
        assertTrue(player.eqEnabled)
        assertEquals("sw.on", drawn(toggle))

        // the EQ switched off in the app
        player.eqEnabled = false
        rt.tick()
        assertEquals("sw.off", drawn(toggle))
    }

    @Test
    fun `a two-state light for EQ on shows the EQ as it is`() {
        val light = button("eq.light")
        player.eqEnabled = true
        rt.tick()
        assertEquals("eqlight.1", drawn(light))

        light.click()
        assertFalse(player.eqEnabled)
        assertEquals("eqlight.0", drawn(light))

        // the EQ switched on in the app
        player.eqEnabled = true
        rt.tick()
        assertEquals("eqlight.1", drawn(light))
    }

    @Test
    fun `two EQ auto switches light together`() {
        val a = button("auto.a")
        val b = button("auto.b")

        a.click()
        assertEquals("auto.on", drawn(a))
        assertEquals("auto.on", drawn(b))

        b.click()
        assertEquals("auto.off", drawn(a))
        assertEquals("auto.off", drawn(b))
    }

    /** The buttons in [g], and in the groups inside it, that open and close a window. */
    private fun toggles(g: Group): List<Button> =
        g.children.flatMap { o ->
            when (o) {
                is Button -> listOfNotNull(o.takeIf { it.followsHost })
                is Group -> toggles(o)
                else -> emptyList()
            }
        }

    /**
     * Opens the window of each TOGGLE button of [skin]'s main window, in each of its layouts, with
     * the button, and closes it as Back does: the button lights while the window is open and goes
     * dark after. Returns the buttons that opened a window of the skin, as "layout/button".
     */
    private fun checkLights(
        dir: File,
        skin: String,
    ): List<String> {
        val files = ZipSkinFiles(File(dir, "$skin.wal"))
        val rt =
            SkinRuntime(files, TestHost(files), FakePlayer()).apply {
                load()
                viewSize = 1080 to 2400
            }
        val main = checkNotNull(rt.mainContainer)
        val checked = ArrayList<String>()
        // MMD3 has its playlist buttons on its shade bars only
        for (layout in main.layouts.toList()) {
            main.switchTo(layout.id)
            rt.ensureLayout()
            for (b in toggles(layout)) {
                b.click()
                val opened = rt.topWindow
                if (opened == null) {
                    // a window the app shows, or a flag: put it back
                    b.click()
                    continue
                }
                rt.tick()
                val name = "$skin ${layout.id}/${b.id} for ${opened.id}"
                assertTrue("$name lights while the window is open", b.isActive)
                while (rt.topWindow != null) rt.closeTopWindow()
                rt.tick()
                assertFalse("$name goes dark once Back closed the window", b.isActive)
                checked.add("${layout.id}/${b.id}")
            }
        }
        return checked
    }

    @Test
    fun `in the skins whose buttons light for their windows, every such light goes out when Back closes the window`() {
        val dir = File(localResource("/Winamp3x.wal").toURI()).parentFile
        val skins = listOf("WinampModern566", "CornerAmp_Redux", "MMD3", "DeClassified", "Winamp3x", "Winamp2000SP4")
        val checked = skins.associateWith { checkLights(dir, it) }
        println(checked.entries.joinToString("\n") { "${it.key}: ${it.value.joinToString()}" })
        assertTrue("a light checked in each skin: $checked", checked.values.all { it.isNotEmpty() })
    }
}
