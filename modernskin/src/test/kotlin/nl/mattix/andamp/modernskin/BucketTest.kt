// SPDX-License-Identifier: GPL-3.0-or-later
package nl.mattix.andamp.modernskin

import nl.mattix.andamp.modernskin.skin.Button
import nl.mattix.andamp.modernskin.skin.ComponentBucket
import nl.mattix.andamp.modernskin.skin.FakePlayer
import nl.mattix.andamp.modernskin.skin.GroupXFade
import nl.mattix.andamp.modernskin.skin.GuiObject
import nl.mattix.andamp.modernskin.skin.SkinFiles
import nl.mattix.andamp.modernskin.skin.SkinRuntime
import nl.mattix.andamp.modernskin.skin.ToggleButton
import nl.mattix.andamp.modernskin.skin.ZipSkinFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import java.io.File

/**
 * A component bucket shows the groups of its window type and pages through them; a cross-fade
 * group shows the page its `groupid` names. Winamp Modern's Options tab in the config drawer is
 * built from both.
 */
class BucketTest {
    private val files =
        object : SkinFiles {
            private val entries =
                (1..5).joinToString("") { i ->
                    """<groupdef id="entry.$i" windowtype="menu" w="40" h="10">""" +
                        """<button id="btn" action="switchto;page.$i" x="0" y="0" w="40" h="10"/></groupdef>"""
                }
            private val m =
                mapOf(
                    "skin.xml" to
                        """<WinampAbstractionLayer version="0.8">$entries""" +
                        """<groupdef id="page.1"><button id="one" x="0" y="0" w="20" h="20"/></groupdef>""" +
                        """<groupdef id="page.2"><button id="two" x="0" y="0" w="20" h="20"/></groupdef>""" +
                        """<include file="player.xml"/></WinampAbstractionLayer>""",
                    "player.xml" to
                        """<container id="main" name="t" default_visible="1"><layout id="normal" w="200" h="100">""" +
                        """<componentbucket id="my.bucket" x="0" y="10" w="40" h="25" vertical="1" wndtype="menu"/>""" +
                        """<button id="up" action="cb_prevpage" x="0" y="0" w="10" h="10"/>""" +
                        """<button id="down" action="cb_nextpage" x="0" y="40" w="10" h="10"/>""" +
                        """<groupxfade id="target" speed="0.5" x="100" y="0" w="80" h="60"/>""" +
                        """</layout></container>""",
                ).mapValues { it.value.toByteArray() }

            override fun read(path: String): ByteArray? = m[SkinFiles.normalize(path)]

            override fun names(): List<String> = m.keys.toList()
        }

    private var now = 0L

    private val rt =
        SkinRuntime(files, TestHost(files), FakePlayer().apply { clock = { 0L } }).apply {
            clock = { now }
            load()
            viewSize = 200 to 400
        }

    private fun find(id: String): GuiObject = checkNotNull(rt.mainLayout?.findObject(id)) { id }

    private val bucket get() = find("my.bucket") as ComponentBucket

    @Test
    fun `a bucket holds the groups of its window type, stacked in the order they were defined`() {
        rt.tick()
        val tops = bucket.children.map { it.rect.top - bucket.rect.top }
        assertEquals(listOf(0, 10, 20, 30, 40), tops)
    }

    @Test
    fun `the page buttons scroll by the entries the bucket shows, no further than the last`() {
        rt.tick()
        (find("down") as Button).click()
        assertEquals("three entries show, partly the third", 25, bucket.scroll)
        (find("down") as Button).click()
        assertEquals(25, bucket.scroll)
        (find("up") as Button).click()
        assertEquals(0, bucket.scroll)
    }

    @Test
    fun `an entry scrolled out of the bucket takes no touch`() {
        rt.tick()
        val last = bucket.children.last()
        assertNull(bucket.hitTest(last.rect.left + 1, last.rect.top + 1))
        val first = bucket.children.first()
        assertNotNull(bucket.hitTest(first.rect.left + 1, first.rect.top + 1))
    }

    @Test
    fun `a cross-fade group shows the page its groupid names, filling it, and fades to the next`() {
        val target = find("target") as GroupXFade
        target.setXmlParam("groupid", "page.1")
        rt.tick()
        val one = target.findObject("one")!!.parent!!
        assertEquals(target.rect, one.rect)

        target.setXmlParam("groupid", "page.2")
        now = 250
        rt.tick()
        val two = target.findObject("two")!!.parent!!
        assertEquals("halfway", 128, two.alpha, 2)
        now = 600
        rt.tick()
        assertEquals(255, two.alpha)
        assertEquals(0, one.alpha)
        assertSame(
            "the page shown takes the touch",
            target.findObject("two"),
            target.hitTest(two.rect.left + 1, two.rect.top + 1),
        )

        target.setXmlParam("groupid", "page.1")
        assertSame("a page shown again is the one made before", one, target.findObject("one")!!.parent)
    }

    @Test
    fun `Winamp Modern's Options tab shows its menu and the Drawers page`() {
        val wal = ZipSkinFiles(File(localResource("/WinampModern566.wal").toURI()))
        val host = TestHost(wal)
        val rt = SkinRuntime(wal, host, FakePlayer().apply { clock = { 0L } }).apply { clock = { 0L } }
        rt.load()
        repeat(5) { rt.tick() }

        val layout = checkNotNull(rt.mainLayout)
        val menu = layout.findObject("my.bucket") as ComponentBucket
        assertEquals(7, menu.children.size)
        val target = layout.findObject("skin.config.target") as GroupXFade
        val box =
            target.children
                .filter { it.visible }
                .flatMap { (it as nl.mattix.andamp.modernskin.skin.Group).children }
                .filterIsInstance<ToggleButton>()
                .mapNotNull { it.cfgAttrib }
        assertEquals(true, "animate config drawer" in box)
    }
}

private fun assertEquals(
    message: String,
    expected: Int,
    actual: Int,
    delta: Int,
) = org.junit.Assert.assertEquals(message, expected.toDouble(), actual.toDouble(), delta.toDouble())
