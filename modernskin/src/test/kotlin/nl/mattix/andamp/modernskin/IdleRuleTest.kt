// SPDX-License-Identifier: GPL-3.0-or-later
package nl.mattix.andamp.modernskin

import nl.mattix.andamp.modernskin.maki.Value
import nl.mattix.andamp.modernskin.skin.DrawCmd
import nl.mattix.andamp.modernskin.skin.FakePlayer
import nl.mattix.andamp.modernskin.skin.FontDef
import nl.mattix.andamp.modernskin.skin.GuiObject
import nl.mattix.andamp.modernskin.skin.SkinFiles
import nl.mattix.andamp.modernskin.skin.SkinHost
import nl.mattix.andamp.modernskin.skin.SkinRuntime
import nl.mattix.andamp.modernskin.skin.Timer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a skin tells the app about resting: whether something on it moves smoothly enough to want a
 * frame on every display frame ([SkinRuntime.framesWanted]), and when it next changes by itself
 * ([SkinRuntime.nextDueAt]), for a skin that rests between those moments.
 */
class IdleRuleTest {
    private fun files(body: String) =
        object : SkinFiles {
            private val m =
                mapOf(
                    "skin.xml" to
                        """<WinampAbstractionLayer version="0.8"><elements><bitmap id="strip" file="strip.png"/></elements>""" +
                        """<include file="player.xml"/></WinampAbstractionLayer>""",
                    "player.xml" to
                        """<container id="main" name="t" default_visible="1"><layout id="normal" w="200" h="100">$body""" +
                        """</layout></container>""",
                ).mapValues { it.value.toByteArray() }

            override fun read(path: String): ByteArray? = m[SkinFiles.normalize(path)]

            override fun names(): List<String> = m.keys.toList()
        }

    private val host =
        object : SkinHost {
            // a strip of five 10 x 10 frames
            override fun imageSize(file: String): Pair<Int, Int>? = if (file == "strip.png") 10 to 50 else null

            override fun textWidth(
                text: String,
                font: FontDef?,
                size: Int,
            ) = text.length * 6

            override fun log(message: String) = Unit

            override fun invalidate() = Unit
        }

    private var t = 1_000_000L

    // the player's own clock: it moves with the runtime's unless a test holds it, as a player
    // waiting for its sound does
    private var playerHeld: Long? = null
    private val player = FakePlayer().apply { clock = { playerHeld ?: t } }

    private fun runtime(body: String) =
        SkinRuntime(files(body), host, player).apply {
            clock = { t }
            load()
            drawWindows()
        }

    private fun SkinRuntime.find(id: String): GuiObject = checkNotNull(mainLayout?.findObject(id))

    /** Ticks [rt] every [every] ms for [ms] ms, drawing the frames it asks for; returns how many it asked for. */
    private fun run(
        rt: SkinRuntime,
        ms: Long,
        every: Long = 16,
    ): Int {
        var asked = 0
        val end = t + ms
        while (t < end) {
            t = minOf(t + every, end)
            if (rt.tick()) {
                asked++
                rt.drawWindows()
            }
        }
        return asked
    }

    /**
     * Wakes [rt] only when [SkinRuntime.nextDueAt] says, as a resting app does, for [ms] ms, at
     * least [floor] ms apart; returns how many frames it asked for.
     */
    private fun wakeWhenDue(
        rt: SkinRuntime,
        ms: Long,
        floor: Long = 16,
    ): Int {
        var asked = 0
        val end = t + ms
        var last = t
        while (true) {
            val due = rt.nextDueAt() ?: break
            val at = maxOf(due, last + floor)
            if (at > end) break
            t = at
            last = at
            if (rt.tick()) {
                asked++
                rt.drawWindows()
            }
        }
        t = end
        return asked
    }

    /** How far the skin's one ticker is scrolled in the frame drawn now. */
    private fun scrollOf(rt: SkinRuntime): Int =
        rt
            .draw()
            .filterIsInstance<DrawCmd.Text>()
            .single()
            .scroll

    @Test
    fun `a stopped skin wants no frames and is next due when its timer is`() {
        val rt = runtime("""<vis id="vis" x="0" y="0" w="72" h="16"/>""")
        assertFalse("a visualizer at rest moves nothing", rt.framesWanted)
        assertNull("nothing will change", rt.nextDueAt())

        val timer = Timer(rt).apply { delay = 500 }
        timer.start()
        assertFalse(rt.framesWanted)
        assertEquals(t + 500, rt.nextDueAt())

        t += 500
        rt.tick()
        assertEquals("fired, it is due again a delay later", t + 500, rt.nextDueAt())
        timer.stop()
        assertNull(rt.nextDueAt())
    }

    @Test
    fun `a paused animated layer neither animates nor wants frames`() {
        val rt = runtime("""<animatedlayer id="strip" x="0" y="0" w="10" h="10" image="strip" autoplay="1" speed="100"/>""")
        player.play()
        rt.tick()
        assertTrue(rt.animating)
        assertTrue("playing, it wants frames", rt.framesWanted)
        assertEquals(t + 100, rt.nextDueAt())

        rt.find("strip").makiCall("pause", emptyList())
        rt.drawWindows()
        assertFalse(rt.animating)
        assertFalse(rt.framesWanted)
        assertNull(rt.nextDueAt())
        assertEquals("a paused strip shows one frame", 0, run(rt, 1_000))

        rt.find("strip").makiCall("pause", emptyList())
        assertTrue("resumed", rt.framesWanted)
    }

    @Test
    fun `a strip out of sight steps on for the scripts but asks for no frames`() {
        val rt =
            runtime(
                """<group id="folded" x="0" y="20" w="10" h="10" visible="0">""" +
                    """<animatedlayer id="strip" x="0" y="0" w="10" h="10" image="strip" autoplay="1" speed="50"/></group>""",
            )
        player.play()
        rt.tick()
        rt.drawWindows()
        val strip = rt.find("strip")
        assertFalse(rt.framesWanted)
        assertEquals("it still steps on time", t + 50, rt.nextDueAt())
        val frame = strip.makiCall("getcurframe", emptyList())?.asInt
        assertEquals("but draws nothing", 0, wakeWhenDue(rt, 1_060))
        assertEquals(
            "21 steps on, a strip of 5 shows its next frame",
            (checkNotNull(frame) + 1) % 5,
            strip.makiCall("getcurframe", emptyList())?.asInt,
        )

        rt.find("folded").makiCall("show", emptyList())
        assertTrue("shown, it wants frames", rt.framesWanted)
        val shown = wakeWhenDue(rt, 1_000)
        assertTrue("and asks for them: $shown", shown > 10)
    }

    @Test
    fun `a playing skin with a shown visualizer wants frames, one with its visualizer off does not`() {
        val rt = runtime("""<vis id="vis" x="0" y="0" w="72" h="16"/><vis id="hidden" x="0" y="20" w="72" h="16" visible="0"/>""")
        assertFalse(rt.framesWanted)
        player.play()
        rt.tick()
        assertTrue(rt.framesWanted)

        rt.find("vis").makiCall("setmode", listOf(Value.Int(0)))
        run(rt, 100)
        assertFalse("off, and the other one hidden", rt.framesWanted)
        assertNull(rt.nextDueAt())
    }

    @Test
    fun `a scrolling text ticker is next due while playing or paused and holds at its start while stopped`() {
        // the title is 150 px wide in a 40 px box
        val rt = runtime("""<text id="title" display="songname" ticker="1" x="0" y="0" w="40" h="12"/>""")
        run(rt, 10_000, every = 250)
        assertEquals("stopped, it holds still", 0, scrollOf(rt))
        assertNull(rt.nextDueAt())

        player.play()
        rt.tick()
        assertFalse("a ticker never wants a frame loop", rt.framesWanted)
        val due = checkNotNull(rt.nextDueAt())
        assertTrue("it waits out its pause at the start: $due", due >= t + 4_000)
        assertTrue("its steps ask for frames", wakeWhenDue(rt, 6_000) > 10)
        val playing = scrollOf(rt)
        assertTrue("it scrolled: $playing", playing > 0)

        player.pause()
        rt.tick()
        assertTrue("paused, it scrolls on", wakeWhenDue(rt, 1_000) > 5)
        assertTrue(scrollOf(rt) > playing)

        player.stop()
        assertTrue("stopping puts it back at the start", rt.tick())
        assertEquals(0, scrollOf(rt))
        assertNull(rt.nextDueAt())
        run(rt, 2_000, every = 250)
        assertEquals("and it holds there", 0, scrollOf(rt))
    }

    @Test
    fun `a ticker goes on from where it held, however long the skin rested`() {
        val rt = runtime("""<text id="title" display="songname" ticker="1" x="0" y="0" w="40" h="12"/>""")
        player.play()
        wakeWhenDue(rt, 6_000)
        player.stop()
        rt.tick()
        // resting: no wakes for a minute
        t += 60_000
        player.play()
        rt.tick()
        assertEquals("the first frame after the rest shows it where it held", 0, scrollOf(rt))
        assertTrue("then it waits out its pause at the start", checkNotNull(rt.nextDueAt()) >= t + 4_000)
        wakeWhenDue(rt, 3_900)
        assertEquals(0, scrollOf(rt))

        // back from a temporary message, the title starts over and waits its pause, with no jump
        wakeWhenDue(rt, 1_000)
        assertTrue(scrollOf(rt) > 0)
        rt.find("title").makiCall("setalternatetext", listOf(Value.Str("Volume: 50%")))
        wakeWhenDue(rt, 1_000)
        rt.tick()
        assertEquals(0, scrollOf(rt))
        wakeWhenDue(rt, 3_900)
        assertEquals(0, scrollOf(rt))
    }

    @Test
    fun `a songticker goes on after a pause from where it held, however long the skin rested`() {
        val rt = runtime("""<songticker id="title" x="0" y="0" w="40" h="12"/>""")
        player.play()
        wakeWhenDue(rt, 2_000)
        player.pause()
        rt.tick()
        val held = scrollOf(rt)
        assertTrue("it scrolled: $held", held > 0)
        assertNull(rt.nextDueAt())
        t += 60_000
        player.play()
        rt.tick()
        assertEquals("it goes on from where it held, one step on", held + 1, scrollOf(rt))
        assertEquals(t + 39, rt.nextDueAt())
    }

    @Test
    fun `a songticker is next due only while playing`() {
        val rt = runtime("""<songticker id="title" x="0" y="0" w="40" h="12"/>""")
        assertNull(rt.nextDueAt())
        player.play()
        rt.tick()
        assertEquals("one pixel every 39 ms", t + 39, rt.nextDueAt())
        player.pause()
        rt.tick()
        assertNull(rt.nextDueAt())
    }

    @Test
    fun `a temporary message is next due when it should go back`() {
        val rt = runtime("""<text id="info" text="Andamp" x="0" y="0" w="100" h="12"/>""")
        rt.find("info").makiCall("setalternatetext", listOf(Value.Str("Volume: 50%")))
        assertEquals(t + 1_000, rt.nextDueAt())

        t += 1_000
        assertTrue("it went back", rt.tick())
        assertNull(rt.nextDueAt())
    }

    @Test
    fun `a target slide is due at once and still ends on time when ticked four times a second`() {
        val rt = runtime("""<layer id="box" x="0" y="0" w="10" h="10"/>""")
        val box = rt.find("box")
        box.makiCall("settargetx", listOf(Value.Int(100)))
        box.makiCall("settargetspeed", listOf(Value.Num(1.0)))
        box.makiCall("gototarget", emptyList())
        assertEquals(t, rt.nextDueAt())
        assertFalse("stopped, a slide steps when due, with no frame loop", rt.framesWanted)

        run(rt, 750, every = 250)
        assertTrue("on its way", box.laidOut().left in 1 until 100)
        run(rt, 250, every = 250)
        assertEquals("there after its second", 100, box.laidOut().left)
        assertFalse(rt.animating)
        assertNull(rt.nextDueAt())
    }

    @Test
    fun `a progress grid alone gives no frame loop while playing and still moves each second`() {
        // 100 px over a 100 s song: a pixel a second
        player.lengthMs = 100_000
        val rt = runtime("""<progressgrid id="seek" x="0" y="0" w="100" h="8"/>""")
        player.play()
        rt.tick()
        rt.drawWindows()
        assertFalse(rt.framesWanted)
        assertEquals("its next pixel", t + 1_000, rt.nextDueAt())
        assertEquals("one frame for each pixel over 10 s", 10, wakeWhenDue(rt, 10_000))
    }

    @Test
    fun `a progress grid that fills several pixels a second moves once a second, with the time`() {
        // 100 px over a 25 s song: 4 pixels a second
        player.lengthMs = 25_000
        val rt =
            runtime(
                """<progressgrid id="seek" x="0" y="0" w="100" h="8"/>""" +
                    """<text id="clock" display="time" x="0" y="10" w="60" h="12"/>""",
            )
        player.play()
        rt.tick()
        rt.drawWindows()
        assertEquals("at the next whole second", t + 1_000, rt.nextDueAt())
        assertEquals("a frame a second", 10, wakeWhenDue(rt, 10_000))
    }

    @Test
    fun `a progress grid whose player waits for its sound asks again a second later, not on every frame`() {
        player.lengthMs = 100_000
        val rt = runtime("""<progressgrid id="seek" x="0" y="0" w="100" h="8"/>""")
        player.play()
        t += 999
        rt.tick()
        rt.drawWindows()
        assertEquals(t + 1, rt.nextDueAt())
        // the place stands still for 5 s
        playerHeld = t
        t += 1
        assertFalse(rt.tick())
        var wakes = 0
        val end = t + 5_000
        while (true) {
            val due = checkNotNull(rt.nextDueAt())
            if (due > end) break
            t = maxOf(due, t + 16)
            wakes++
            rt.tick()
        }
        assertTrue("about one a second: $wakes", wakes in 4..6)
    }
}
