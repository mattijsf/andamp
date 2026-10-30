// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.wal

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import nl.mattix.andamp.core.model.Track
import nl.mattix.andamp.core.model.Transport
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
import nl.mattix.andamp.modernskin.skin.GuiObject
import nl.mattix.andamp.modernskin.skin.SkinFiles
import nl.mattix.andamp.modernskin.skin.SkinRuntime
import nl.mattix.andamp.modernskin.skin.Slider
import nl.mattix.andamp.state.WinampState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * A skin's sliders over the app's player: the engine reads every value back at once and tells the
 * scripts of any change, while the backend reports a write only later. Each value the listener
 * sets is heard once, and a thumb stays where it was let go.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WalPlayerEchoTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    // four sliders 1020 px wide, so each of their 256 steps is 4 px, and a VOLUME_UP button; the
    // script gives the skin a System object for the recorder to listen on
    private val files =
        object : SkinFiles {
            private val m =
                mapOf(
                    "skin.xml" to
                        """<WinampAbstractionLayer version="0.8"><include file="player.xml"/></WinampAbstractionLayer>""",
                    "player.xml" to
                        """<container id="main" name="t" default_visible="1"><layout id="normal" w="1100" h="200">""" +
                        """<slider id="volume" action="volume" x="10" y="10" w="1020" h="10" orientation="h"/>""" +
                        """<slider id="band" action="eq_band" param="1" x="10" y="40" w="1020" h="10" orientation="h"/>""" +
                        """<slider id="preamp" action="eq_preamp" x="10" y="70" w="1020" h="10" orientation="h"/>""" +
                        """<slider id="seek" action="seek" x="10" y="100" w="1020" h="10" orientation="h"/>""" +
                        """<button id="up" action="VOLUME_UP" x="10" y="130" w="20" h="20"/>""" +
                        """<script id="system" file="scripts/system.maki"/>""" +
                        """</layout></container>""",
                ).mapValues { it.value.toByteArray() } + ("scripts/system.maki" to EMPTY_MAKI)

            override fun read(path: String): ByteArray? = m[SkinFiles.normalize(path)]

            override fun names(): List<String> = m.keys.toList()
        }

    private val state =
        WinampState().apply {
            playlist = List(3) { song(it) }
            currentIndex = 0
            transport = Transport.Paused
            volume = 78
        }
    private val controls = DeferredControls(state)
    private val posted = ArrayDeque<Runnable>()
    private val player =
        WalPlayer(
            controls = controls,
            state = state,
            tapOf = { null },
            positionMs = { controls.reportedMs },
            openFile = {},
            post = { posted += it },
            clock = { controls.now },
        )
    private val rt = SkinRuntime(files, WalHost(files, app.cacheDir), player, clock = { controls.now })

    /** What the recorder script heard, in order: the event and its arguments. */
    private val heard = ArrayList<Pair<String, List<Int>>>()

    private val recorder =
        object : MakiObject {
            override val makiGuid = RECORDER

            override fun makiCall(
                name: String,
                args: List<Value>,
            ): Value = Value.Null.also { heard += name to args.map { it.asInt } }
        }

    private lateinit var volume: Slider
    private lateinit var band: Slider
    private lateinit var preamp: Slider
    private lateinit var seek: Slider

    @Before
    fun load() {
        controls.place(50_000)
        rt.load()
        val layout = checkNotNull(rt.mainLayout)
        volume = layout.findObject("volume") as Slider
        band = layout.findObject("band") as Slider
        preamp = layout.findObject("preamp") as Slider
        seek = layout.findObject("seek") as Slider
        listen(layout.systemObjects.single())
        // the load placed the thumbs on the player's values, which scripts do not hear
        frame()
        heard.clear()
    }

    /**
     * A script that records the volume, band and preamp events of [system], and every slider's
     * onSetPosition, with their arguments.
     */
    private fun listen(system: MakiObject) {
        val methods = ArrayList<MethodRef>()

        fun method(
            cls: Int,
            name: String,
        ): Int = methods.size.also { methods += MethodRef(cls, name) }
        val code = ArrayList<Op>()
        val bindings = ArrayList<Binding>()

        // a handler that hands its [argc] arguments to the recorder under [name]
        fun record(
            variable: Int,
            event: String,
            name: String,
            argc: Int,
        ) {
            bindings += Binding(variable, method(SOURCE_CLASS, event), code.size)
            val call = method(RECORDER_CLASS, name)
            val temps = listOf(FIRST_VAR, SECOND_VAR).take(argc)
            for (t in temps) code += Op(Opcodes.POP_TO, t, -1, code.size)
            code += Op(Opcodes.PUSH, RECORDER_VAR, -1, code.size)
            for (t in temps.asReversed()) code += Op(Opcodes.PUSH, t, -1, code.size)
            code += Op(Opcodes.CALL, call, argc, code.size)
            code += Op(Opcodes.POP, 0, -1, code.size)
            code += Op(Opcodes.PUSH, ZERO_VAR, -1, code.size)
            code += Op(Opcodes.RETURN, 0, -1, code.size)
        }
        record(SYSTEM_VAR, "onvolumechanged", "volume", 1)
        record(SYSTEM_VAR, "oneqbandchanged", "band", 2)
        record(SYSTEM_VAR, "oneqpreampchanged", "preamp", 1)
        for ((i, id) in SLIDERS.withIndex()) record(SLIDER_VARS + i, "onsetposition", "$id.position", 1)

        fun obj(o: MakiObject) = VarDecl(Kind.OBJECT, guid = null, classVar = null, global = true, initial = Value.Obj(o))

        fun int() = VarDecl(Kind.INT, guid = null, classVar = null, global = true, initial = Value.Int(0))
        val program =
            MakiProgram(
                id = "recorder",
                version = 0,
                classes = listOf(StdApi.guidOf("GuiObject")!!, RECORDER),
                methods = methods,
                variables =
                    listOf(obj(system), obj(recorder), int(), int(), int()) +
                        listOf(volume, band, preamp, seek).map(::obj),
                bindings = bindings,
                code = code,
            )
        rt.scripts += Script(program, system, rt)
    }

    /** One display frame: the engine ticks, main runs what was posted, the clock moves on. */
    private fun frame() {
        rt.tick()
        while (posted.isNotEmpty()) posted.removeFirst().run()
        controls.advance(FRAME_MS)
    }

    private var frameCount = 0

    /** [n] frames; the backend reports what it was asked every [lag] frames. */
    private fun frames(
        n: Int,
        lag: Int = 2,
    ) {
        repeat(n) {
            frame()
            if (++frameCount % lag == 0) controls.apply()
        }
    }

    /** A press on [s] at its step [position]. */
    private fun x(
        s: Slider,
        position: Int,
    ) = s.rect.left + 4 * position + 1

    private fun y(s: GuiObject) = s.rect.top + 5

    /**
     * Drags [s] through [positions], a frame each, and lets go at the last. The backend reports each
     * value only once the next one is set, so the engine sees the values on the way.
     */
    private fun drag(
        s: Slider,
        positions: List<Int>,
    ) {
        rt.pointerDown(x(s, positions.first()), y(s))
        frame()
        for (p in positions.drop(1)) {
            rt.pointerMove(x(s, p), y(s))
            controls.apply()
            frame()
        }
        rt.pointerUp(x(s, positions.last()), y(s))
    }

    private fun heard(name: String) = heard.filter { it.first == name }.map { it.second }

    /** The slider moves the phone's volume, which has [steps] steps and sits on [step]. */
    private fun phoneVolume(
        steps: Int,
        step: Int,
    ) {
        controls.volumeSteps = steps
        state.volume = (step * 100f / steps).roundToInt()
        frame()
        heard.clear()
    }

    /** Drags the volume on a phone with [steps] volume steps, and checks each value is heard once and the thumb stays. */
    private fun dragOnSteps(steps: Int) {
        phoneVolume(steps, (0.78f * steps).roundToInt())
        val positions = listOf(150, 160, 170, 180, 196, 200, 202)

        drag(volume, positions)
        // past the second after which a write still on the step it started from counts as landed
        repeat(100) {
            frames(1)
            assertEquals("the thumb after the release, frame $it", 202, volume.position)
        }

        assertEquals(positions.map { listOf(it) }, heard("volume"))
        assertEquals(positions.map { listOf(it) }, heard("volume.position"))
        assertEquals(((202 / 255f * steps).roundToInt() * 100f / steps).roundToInt(), state.volume)
        assertEquals(202, player.volume)
    }

    @Test
    fun `the thumbs show the player's values from the skin's first frame, before any tick`() {
        state.volume = 20
        state.eqBands[0] = 10
        state.preamp = 50
        val fresh = SkinRuntime(files, WalHost(files, app.cacheDir), player, clock = { controls.now })

        // the engine publishes the skin's first frame right after the load
        fresh.load()

        val layout = checkNotNull(fresh.mainLayout)

        fun thumbs() = SLIDERS.map { (layout.findObject(it) as Slider).position }
        val first = thumbs()
        // an equalizer slider runs from -128 to 127, flat at 0
        assertEquals(listOf(WalPlayer.fromPercent(20), WalPlayer.fromEq(10) - 128, WalPlayer.fromEq(50) - 128, 50), first)
        fresh.tick()
        assertEquals("the first tick finds them in place", first, thumbs())
    }

    @Test
    fun `each volume set is heard once and the thumb stays where it was let go`() {
        val positions = listOf(150, 160, 170, 180, 200)

        drag(volume, positions)
        repeat(40) {
            frames(1)
            assertEquals("the thumb after the release, frame $it", 200, volume.position)
        }

        assertEquals(positions.map { listOf(it) }, heard("volume"))
        assertEquals(positions.map { listOf(it) }, heard("volume.position"))
        assertEquals(78, state.volume)
        assertEquals(200, player.volume)
    }

    @Test
    fun `each band value set is heard once and the thumb stays`() {
        // 140, 141 and 142 all become 35 on the app's scale
        val positions = listOf(100, 140, 141, 142, 250, 3)

        drag(band, positions)
        frames(40)

        assertEquals(positions.map { listOf(0, it - 128) }, heard("band"))
        assertEquals(positions.map { listOf(it - 128) }, heard("band.position"))
        assertEquals(3 - 128, band.position)
        assertEquals(WalPlayer.toEq(3), state.eqBands[0])
    }

    @Test
    fun `each preamp value set is heard once and the thumb stays`() {
        val positions = listOf(100, 101, 102, 255)

        drag(preamp, positions)
        frames(40)

        assertEquals(positions.map { listOf(it - 128) }, heard("preamp"))
        assertEquals(positions.map { listOf(it - 128) }, heard("preamp.position"))
        assertEquals(127, preamp.position)
    }

    @Test
    fun `a volume let go at 200, then the volume keys at 76, is heard as 200 and 194`() {
        assertEquals(78, state.volume)

        rt.pointerDown(x(volume, 200), y(volume))
        rt.pointerUp(x(volume, 200), y(volume))
        frames(20)
        state.volume = 76
        frames(20)

        assertEquals(listOf(listOf(200), listOf(194)), heard("volume"))
        assertEquals(194, volume.position)
    }

    @Test
    fun `on a phone's 15 volume steps, each volume set is heard once and the thumb stays`() = dragOnSteps(15)

    @Test
    fun `on a phone's 25 volume steps, each volume set is heard once and the thumb stays`() = dragOnSteps(25)

    @Test
    fun `on a phone's volume steps, a volume let go at 200, then a key press down, is heard as 200 and the step below`() {
        for ((steps, step, below) in listOf(Triple(25, 19, 194), Triple(15, 11, 186))) {
            phoneVolume(steps, step)

            rt.pointerDown(x(volume, 200), y(volume))
            rt.pointerUp(x(volume, 200), y(volume))
            frames(20)
            assertEquals("$steps steps: landed on the step above", ((step + 1) * 100f / steps).roundToInt(), state.volume)
            state.volume = (step * 100f / steps).roundToInt()
            frames(20)

            assertEquals("$steps steps", listOf(listOf(200), listOf(below)), heard("volume"))
            assertEquals(below, volume.position)
        }
    }

    @Test
    fun `volume up twice before main runs adds twenty, heard once`() {
        val up = checkNotNull(rt.mainLayout?.findObject("up"))
        val start = player.volume

        repeat(2) {
            rt.pointerDown(up.rect.left + 5, y(up))
            rt.pointerUp(up.rect.left + 5, y(up))
        }
        frames(10)

        assertEquals(listOf(listOf(start + 20)), heard("volume"))
        assertEquals(listOf("setVolume(${(start + 20) / 255f})"), controls.calls)
        assertEquals(start + 20, volume.position)
    }

    @Test
    fun `a seek let go while paused stays where it was let go, through the backend's lag`() {
        assertEquals(50, seek.position)

        rt.pointerDown(x(seek, 100), y(seek))
        rt.pointerUp(x(seek, 100), y(seek))
        // 300 ms before the backend has it
        repeat(19) {
            frame()
            assertEquals("the thumb while the backend seeks, frame $it", 100, seek.position)
        }
        controls.apply()
        repeat(20) {
            frame()
            assertEquals("the thumb once the backend has it, frame $it", 100, seek.position)
        }

        assertEquals(listOf(listOf(100)), heard("seek.position"))
        assertEquals(1, controls.calls.count { it.startsWith("seekTo") })
        assertTrue("the backend is there: ${controls.reportedMs}", controls.reportedMs in 99_000L..100_000L)
    }

    @Test
    fun `a seek let go while playing never steps back while the backend catches up`() {
        state.transport = Transport.Playing
        frames(10)
        assertEquals(50, seek.position)

        rt.pointerDown(x(seek, 100), y(seek))
        rt.pointerUp(x(seek, 100), y(seek))
        val thumbs = ArrayList<Int>()
        repeat(19) {
            frame()
            thumbs += seek.position
        }
        controls.apply()
        repeat(120) {
            frame()
            thumbs += seek.position
        }

        assertEquals("let go at 100", 100, thumbs.first())
        for (i in 1 until thumbs.size) assertTrue("never back: $thumbs", thumbs[i] >= thumbs[i - 1])
        assertTrue("and on with the song: ${thumbs.last()}", thumbs.last() in 101..103)
    }

    @Test
    fun `a seek drag that is cancelled seeks nothing`() {
        rt.pointerDown(x(seek, 100), y(seek))
        frames(2)
        rt.pointerCancel()
        frames(10)

        assertTrue(controls.calls.none { it.startsWith("seekTo") })
        assertEquals("the thumb is back on the song's place", 50, seek.position)
    }

    private companion object {
        const val FRAME_MS = 16L
        const val RECORDER = "recorder"
        const val SOURCE_CLASS = 0
        const val RECORDER_CLASS = 1
        const val SYSTEM_VAR = 0
        const val RECORDER_VAR = 1
        const val FIRST_VAR = 2
        const val SECOND_VAR = 3
        const val ZERO_VAR = 4
        const val SLIDER_VARS = 5
        val SLIDERS = listOf("volume", "band", "preamp", "seek")

        /** A song whose length makes every seek bar step a whole second: 255 steps over 255 s. */
        fun song(n: Int) = Track("song-$n", "Artist $n", "Song $n", 255_000, uri = "file:///music/$n.mp3")

        /** A `.maki` file with no classes, variables or code: enough for the skin to make a System object. */
        val EMPTY_MAKI: ByteArray =
            ByteBuffer
                .allocate(32)
                .order(ByteOrder.LITTLE_ENDIAN)
                .put("FG".toByteArray())
                .putShort(1027)
                .putInt(23)
                .apply { repeat(6) { putInt(0) } }
                .array()
    }
}
