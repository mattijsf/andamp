// SPDX-License-Identifier: GPL-3.0-or-later
package nl.mattix.andamp.modernskin

import nl.mattix.andamp.modernskin.skin.Button
import nl.mattix.andamp.modernskin.skin.DrawCmd
import nl.mattix.andamp.modernskin.skin.FakePlayer
import nl.mattix.andamp.modernskin.skin.FontDef
import nl.mattix.andamp.modernskin.skin.Player
import nl.mattix.andamp.modernskin.skin.SkinFiles
import nl.mattix.andamp.modernskin.skin.SkinHost
import nl.mattix.andamp.modernskin.skin.SkinRuntime
import nl.mattix.andamp.modernskin.skin.ZipSkinFiles
import org.junit.Assert.assertEquals
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
