// SPDX-License-Identifier: GPL-3.0-or-later
package nl.mattix.andamp.modernskin

import nl.mattix.andamp.modernskin.maki.Binding
import nl.mattix.andamp.modernskin.maki.Kind
import nl.mattix.andamp.modernskin.maki.MakiObject
import nl.mattix.andamp.modernskin.maki.MakiProgram
import nl.mattix.andamp.modernskin.maki.MethodRef
import nl.mattix.andamp.modernskin.maki.Op
import nl.mattix.andamp.modernskin.maki.Opcodes
import nl.mattix.andamp.modernskin.maki.Script
import nl.mattix.andamp.modernskin.maki.StdApi
import nl.mattix.andamp.modernskin.maki.Value
import nl.mattix.andamp.modernskin.maki.VarDecl
import nl.mattix.andamp.modernskin.skin.FakePlayer
import nl.mattix.andamp.modernskin.skin.FontDef
import nl.mattix.andamp.modernskin.skin.Player
import nl.mattix.andamp.modernskin.skin.SkinFiles
import nl.mattix.andamp.modernskin.skin.SkinHost
import nl.mattix.andamp.modernskin.skin.SkinRuntime
import nl.mattix.andamp.modernskin.skin.Slider
import nl.mattix.andamp.modernskin.skin.Text
import nl.mattix.andamp.modernskin.skin.ZipSkinFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * A slider press the host cancels, as a long press does: the scripts hear the button go up, so
 * what they show while the thumb is held gives way, and nothing a release does happens: no final
 * position, no seek.
 */
class SliderCancelTest {
    // a seek bar and a volume slider
    private val files =
        object : SkinFiles {
            private val m =
                mapOf(
                    "skin.xml" to
                        """<WinampAbstractionLayer version="0.8"><include file="player.xml"/></WinampAbstractionLayer>""",
                    "player.xml" to
                        """<container id="main" name="t" default_visible="1"><layout id="normal" w="300" h="100">""" +
                        """<slider id="seek" action="seek" x="10" y="10" w="200" h="10" orientation="h"/>""" +
                        """<slider id="volume" action="volume" x="10" y="50" w="100" h="10" orientation="h"/>""" +
                        """</layout></container>""",
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
            ) = text.length * 6

            override fun log(message: String) = Unit

            override fun invalidate() = Unit
        }

    private var now = 0L
    private val player = FakePlayer().apply { lengthMs = 200_000 }
    private val rt = SkinRuntime(files, host, player).apply { clock = { now } }

    /** The slider events the script heard, in order. */
    private val heard = ArrayList<String>()

    private val recorder =
        object : MakiObject {
            override val makiGuid = RECORDER

            override fun makiCall(
                name: String,
                args: List<Value>,
            ): Value = Value.Null.also { heard += name }
        }

    private lateinit var seek: Slider
    private lateinit var volume: Slider

    @Before
    fun load() {
        player.clock = { now }
        rt.load()
        seek = checkNotNull(rt.mainLayout?.findObject("seek")) as Slider
        volume = checkNotNull(rt.mainLayout?.findObject("volume")) as Slider
    }

    /** A script that records the press, release and final position events of [target]. */
    private fun record(target: Slider) {
        // each event's arguments dropped, then its name recorded
        val events = listOf("onleftbuttondown" to 2, "onleftbuttonup" to 2, "onsetfinalposition" to 1, "onpostedposition" to 1)
        val methods = ArrayList<MethodRef>()
        val code = ArrayList<Op>()
        val bindings =
            events.map { (event, args) ->
                val start = code.size
                repeat(args) { code += Op(Opcodes.POP, 0, -1, code.size) }
                methods += MethodRef(RECORDER_CLASS, event.removePrefix("on"))
                code += Op(Opcodes.PUSH, RECORDER_VAR, -1, code.size)
                code += Op(Opcodes.CALL, methods.lastIndex, 0, code.size)
                code += Op(Opcodes.POP, 0, -1, code.size)
                code += Op(Opcodes.PUSH, ZERO_VAR, -1, code.size)
                code += Op(Opcodes.RETURN, 0, -1, code.size)
                methods += MethodRef(TARGET_CLASS, event)
                Binding(TARGET_VAR, methods.lastIndex, start)
            }
        val program =
            MakiProgram(
                id = "slider",
                version = 0,
                classes = listOf(StdApi.guidOf("Slider")!!, RECORDER),
                methods = methods,
                variables =
                    listOf(
                        VarDecl(Kind.OBJECT, guid = null, classVar = null, global = true, initial = Value.Obj(target)),
                        VarDecl(Kind.OBJECT, guid = null, classVar = null, global = true, initial = Value.Obj(recorder)),
                        VarDecl(Kind.INT, guid = null, classVar = null, global = true, initial = Value.Int(0)),
                    ),
                bindings = bindings,
                code = code,
            )
        rt.scripts += Script(program, recorder, rt)
    }

    private fun tick(ms: Long) {
        now += ms
        rt.tick()
    }

    @Test
    fun `a cancelled seek press ends with the button going up, no final position and no seek, and the thumb returns`() {
        record(seek)
        player.play()
        tick(10_000)
        val playing = seek.position

        rt.pointerDown(seek.rect.left + 150, seek.rect.top + 5)
        rt.pointerCancel()
        rt.pointerUp(seek.rect.left + 150, seek.rect.top + 5)

        assertEquals("the release after the cancel goes nowhere", listOf("leftbuttondown", "leftbuttonup"), heard)
        assertFalse(seek.dragging)
        tick(100)
        assertTrue("no seek: ${player.positionMs}", player.positionMs in 10_000..10_100)
        assertEquals("the thumb is back at the playing position", playing, seek.position)
        assertFalse("no final position: $heard", "setfinalposition" in heard)
    }

    @Test
    fun `a cancelled volume press keeps the volume it set`() {
        record(volume)
        rt.pointerDown(volume.rect.left + 30, volume.rect.top + 5)
        val set = player.volume

        rt.pointerCancel()
        tick(100)

        assertEquals(listOf("leftbuttondown", "leftbuttonup"), heard)
        assertEquals(set, player.volume)
        assertEquals(set, rt.sliderTarget(volume))
        assertEquals(rt.sliderTarget(volume), volume.position)
    }

    @Test
    fun `a long press on Winamp Modern's seek bar gives the song ticker back without seeking`() {
        val wal = ZipSkinFiles(File(localResource("/WinampModern566.wal").toURI()))
        val player = FakePlayer().apply { clock = { now } }
        val rt = SkinRuntime(wal, TestHost(wal), player).apply { clock = { now } }
        rt.load()
        rt.viewSize = 400 to 1200
        player.play()
        repeat(10) {
            now += 100
            rt.tick()
        }
        val main = checkNotNull(rt.mainLayout)
        val bar = checkNotNull(main.findObject("seekerghost")) as Slider
        val songticker = checkNotNull(main.findObject("songticker"))
        val infoticker = checkNotNull(main.findObject("infoticker")) as Text
        val at = player.positionMs

        rt.pointerDown(bar.rect.left + bar.rect.width / 2, bar.rect.top + bar.rect.height / 2)
        now += 300
        rt.tick()
        rt.pointerCancel()
        // the thumb follows the playing position again, which tells the script each step
        repeat(30) {
            now += 100
            rt.tick()
            assertFalse("no SEEK text: '${infoticker.currentText}'", infoticker.visible)
            assertTrue(songticker.visible)
        }

        assertEquals(Player.Status.PLAYING, player.status)
        assertEquals("no seek", at + 3_300, player.positionMs)
    }

    private companion object {
        const val RECORDER = "recorder"
        const val TARGET_CLASS = 0
        const val RECORDER_CLASS = 1
        const val TARGET_VAR = 0
        const val RECORDER_VAR = 1
        const val ZERO_VAR = 2
    }
}
