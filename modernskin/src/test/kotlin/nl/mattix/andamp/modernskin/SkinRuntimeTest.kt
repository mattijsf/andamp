// SPDX-License-Identifier: GPL-3.0-or-later
package nl.mattix.andamp.modernskin

import nl.mattix.andamp.modernskin.maki.Value
import nl.mattix.andamp.modernskin.skin.Button
import nl.mattix.andamp.modernskin.skin.DrawCmd
import nl.mattix.andamp.modernskin.skin.FakePlayer
import nl.mattix.andamp.modernskin.skin.FontDef
import nl.mattix.andamp.modernskin.skin.Player
import nl.mattix.andamp.modernskin.skin.SkinFiles
import nl.mattix.andamp.modernskin.skin.SkinHost
import nl.mattix.andamp.modernskin.skin.SkinRuntime
import nl.mattix.andamp.modernskin.skin.Slider
import nl.mattix.andamp.modernskin.skin.SystemObject
import nl.mattix.andamp.modernskin.skin.Track
import nl.mattix.andamp.modernskin.skin.ZipSkinFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO

/** A host for tests: image sizes through ImageIO, text 6 px per character, the log kept. */
class TestHost(
    private val files: SkinFiles,
) : SkinHost {
    val log = ArrayList<String>()
    private val sizes = HashMap<String, Pair<Int, Int>?>()

    override fun imageSize(file: String): Pair<Int, Int>? =
        sizes.getOrPut(file.lowercase()) {
            val bytes = files.read(file) ?: return@getOrPut null
            runCatching { ImageIO.read(ByteArrayInputStream(bytes))?.let { it.width to it.height } }.getOrNull()
        }

    override fun textWidth(
        text: String,
        font: FontDef?,
        size: Int,
    ): Int = text.length * size / 2

    private val images = HashMap<String, java.awt.image.BufferedImage?>()

    override fun pixel(
        file: String,
        x: Int,
        y: Int,
    ): Int? =
        images
            .getOrPut(file.lowercase()) {
                files.read(file)?.let { runCatching { ImageIO.read(ByteArrayInputStream(it)) }.getOrNull() }
            }?.takeIf { x < it.width && y < it.height }
            ?.getRGB(x, y)

    override fun log(message: String) {
        log.add(message)
    }

    override fun invalidate() = Unit
}

class SkinRuntimeTest {
    // a main window with a 0..100 volume slider, a playlist window that can be made taller, and two
    // color themes
    private val small =
        object : SkinFiles {
            private val m =
                mapOf(
                    "skin.xml" to
                        """<WinampAbstractionLayer version="0.8"><include file="player.xml"/></WinampAbstractionLayer>""",
                    "player.xml" to
                        """<gammaset id="One"/><gammaset id="Two"/>""" +
                        """<container id="main" name="t" default_visible="1"><layout id="normal" w="200" h="100">""" +
                        """<slider id="volume" action="volume" low="0" high="100" """ +
                        """x="0" y="0" w="100" h="10" orientation="h"/>""" +
                        """</layout></container>""" +
                        """<container id="pl" name="Playlist" default_visible="0"><layout id="normal" w="200" h="80">""" +
                        """<layer id="sizer" resize="bottomright" x="190" y="70" w="10" h="10"/>""" +
                        """</layout></container>""",
                ).mapValues { it.value.toByteArray() }

            override fun read(path: String): ByteArray? = m[SkinFiles.normalize(path)]

            override fun names(): List<String> = m.keys.toList()
        }

    /** The small skin loaded, on a clock that stands still. */
    private fun small(player: FakePlayer = FakePlayer()): SkinRuntime {
        player.clock = { 0L }
        return SkinRuntime(small, TestHost(small), player).apply {
            clock = { 0L }
            load()
        }
    }

    /** Ticks and draws until nothing asks for a frame. */
    private fun settle(rt: SkinRuntime) {
        repeat(10) {
            rt.tick()
            rt.drawWindows()
        }
    }

    @Test
    fun `a private value, a config attribute and a color theme each count as a settings change, an idle tick not`() {
        val rt = small()
        val system = SystemObject(rt, null, "", "test")
        val start = rt.settingsVersion

        system.makiCall("setprivatestring", listOf(Value.Str("skin"), Value.Str("tab"), Value.Str("pl")))
        assertEquals(start + 1, rt.settingsVersion)
        system.makiCall("setprivatestring", listOf(Value.Str("skin"), Value.Str("tab"), Value.Str("pl")))
        assertEquals("the same value again changes nothing", start + 1, rt.settingsVersion)

        val item = checkNotNull(rt.configItem("options", "{guid}"))
        val attribute = checkNotNull(item.makiCall("newattribute", listOf(Value.Str("drawer"), Value.Str("0")))?.asObject)
        attribute.makiCall("setdata", listOf(Value.Str("1")))
        assertEquals(start + 2, rt.settingsVersion)

        rt.selectGammaSet("Two")
        assertEquals(start + 3, rt.settingsVersion)
        rt.selectGammaSet("two")
        assertEquals("the theme already on changes nothing", start + 3, rt.settingsVersion)

        settle(rt)
        assertEquals(start + 3, rt.settingsVersion)
    }

    @Test
    fun `with stretchLast off the last window keeps its height, on it fills the view`() {
        val rt = small()
        rt.viewSize = 200 to 400
        rt.openWindow(checkNotNull(rt.container("pl")))
        val pl = checkNotNull(rt.container("pl")?.current)

        assertEquals(200 to 400, rt.stackSize)
        assertEquals(300, rt.windowRect(pl).height)

        rt.stretchLast = false
        assertEquals(200 to 180, rt.stackSize)
        assertEquals(80, rt.windowRect(pl).height)

        rt.stretchLast = true
        assertEquals(200 to 400, rt.stackSize)
    }

    @Test
    fun `the playlist calls read the player's playlist, and an entry is set only while playing`() {
        val player = FakePlayer().apply { playlist = listOf(Track("One", 1_000), Track("Two", 2_000), Track("Three", 3_000)) }
        val rt = small(player)
        val system = SystemObject(rt, null, "", "test")

        assertEquals(3, system.makiCall("getplaylistlength", emptyList())?.asInt)
        assertEquals(0, system.makiCall("getplaylistindex", emptyList())?.asInt)

        system.makiCall("setplaylistindex", listOf(Value.Int(2)))
        assertEquals("stopped, nothing starts", Player.Status.STOPPED, player.status)
        assertEquals(0, player.trackIndex)

        player.play()
        system.makiCall("setplaylistindex", listOf(Value.Int(2)))
        assertEquals(2, player.trackIndex)
        assertEquals(2, system.makiCall("getplaylistindex", emptyList())?.asInt)
        system.makiCall("setplaylistindex", listOf(Value.Int(9)))
        assertEquals("past the end, nothing changes", 2, player.trackIndex)
    }

    @Test
    fun `a changed playlist or current entry asks for one frame`() {
        val player = FakePlayer()
        val rt = small(player)
        settle(rt)
        assertFalse(rt.tick())

        player.playlist = List(3) { Track("Same", 60_000) }
        assertTrue(rt.tick())
        rt.drawWindows()
        assertFalse(rt.tick())

        player.play()
        settle(rt)
        // the same title and length, so only the current entry changes
        player.playTrack(1)
        assertTrue(rt.tick())
        rt.drawWindows()
        assertFalse(rt.tick())
    }

    @Test
    fun `a slider let go of stays where it was let go`() {
        val player = FakePlayer()
        val rt = small(player)
        settle(rt)
        val slider = checkNotNull(rt.mainLayout?.findObject("volume")) as Slider

        // 35 px in, plus the 2 px a thumbless slider is held by: 37 of 100
        rt.pointerDown(35, 5)
        rt.pointerUp(35, 5)
        assertEquals(37, slider.position)
        assertEquals(94, player.volume)

        repeat(10) {
            rt.tick()
            assertEquals(37, slider.position)
        }
    }

    private fun corner(): Triple<SkinRuntime, TestHost, FakePlayer> {
        val file = File(localResource("/CornerAmp_Redux.wal").toURI())
        val files = ZipSkinFiles(file)
        val host = TestHost(files)
        val player = FakePlayer()
        val rt = SkinRuntime(files, host, player)
        rt.load()
        return Triple(rt, host, player)
    }

    @Test
    fun loadsCornerAmpAndRunsItsScript() {
        val (rt, host, player) = corner()
        println("log:\n  " + host.log.joinToString("\n  "))
        println("containers: " + rt.containers.joinToString { "${it.id}(${it.layouts.joinToString { l -> l.id }})" })
        val main = rt.mainLayout!!
        println("main layout ${main.size} children=${main.children.size}")
        println("children: " + main.children.joinToString { "$it@${it.rect}${if (!it.visible) "(hidden)" else ""}" })
        assertEquals(246 to 228, main.size)
        // the script hid three of the four corner backgrounds and showed one
        val visibleMains = main.children.filter { it.id.startsWith("main") && it.visible }
        assertEquals(1, visibleMains.size)
        val cmds = rt.draw()
        println(
            "draw: ${cmds.size} commands; images=" +
                cmds.filterIsInstance<DrawCmd.Image>().joinToString { it.bitmap.id + "@" + it.dst },
        )
        println("texts: " + cmds.filterIsInstance<DrawCmd.Text>().joinToString { "'${it.text}'@${it.rect}" })
        assertTrue(cmds.any { it is DrawCmd.Image && it.bitmap.id == "player.bg1" })

        // tap Play: it sits at 37,142 and is 39x39
        val play = main.findObject("play") as Button
        println("play at ${play.rect}")
        rt.pointerDown(play.rect.left + 5, play.rect.top + 5)
        rt.pointerUp(play.rect.left + 5, play.rect.top + 5)
        assertEquals(Player.Status.PLAYING, player.status)
        rt.tick()
        val after = rt.draw()
        println("texts after play: " + after.filterIsInstance<DrawCmd.Text>().joinToString { "'${it.text}'" })
        assertTrue(after.any { it is DrawCmd.Text && it.text.contains("demo") })
        // the script shows the seek bar fill when playing
        assertTrue(main.findObject("seekfull")!!.visible)
        println("log after:\n  " + host.log.joinToString("\n  "))
    }

    @Test
    fun volumeDragMovesThumbThroughScript() {
        val (rt, host, player) = corner()
        val vol = rt.mainLayout!!.findObject("volume1")!!
        val thumb = rt.mainLayout!!.findObject("volumethumb")!!
        val before = thumb.y
        rt.pointerDown(vol.rect.left + 2, vol.rect.top + 30)
        rt.pointerUp(vol.rect.left + 2, vol.rect.top + 30)
        rt.tick()
        println("volume ${player.volume}, thumb y $before -> ${thumb.y}; log: ${host.log.takeLast(5)}")
        assertTrue(player.volume < 100)
        assertTrue(thumb.y != before)
    }
}
