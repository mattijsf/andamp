// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.wal

import android.app.Application
import android.os.Looper
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import nl.mattix.andamp.modernskin.maki.Value
import nl.mattix.andamp.modernskin.skin.FakePlayer
import nl.mattix.andamp.modernskin.skin.PopupMenu
import nl.mattix.andamp.modernskin.skin.SkinFiles
import nl.mattix.andamp.modernskin.skin.SkinMenu
import nl.mattix.andamp.modernskin.skin.SkinRuntime
import nl.mattix.andamp.modernskin.skin.Timer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * A modern skin's engine at rest: while the screen shows the skin and its frames want no ticks
 * from the screen, the engine wakes it itself when it is due, at the rate the music allows; it sets
 * no wake while resting or under a menu; and a touch or a tick waiting behind another shares its
 * frame. The runtime's clock is the engine's (Robolectric's), which the tests move by hand.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WalEngineRestTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    // a play button, and a title bar whose double click switches to the shade
    private val files =
        object : SkinFiles {
            private val m =
                mapOf(
                    "skin.xml" to
                        """<WinampAbstractionLayer version="0.8"><include file="player.xml"/></WinampAbstractionLayer>""",
                    "player.xml" to
                        """<container id="main" name="t" default_visible="1"><layout id="normal" w="200" h="100">""" +
                        """<button id="play" action="PLAY" x="10" y="10" w="20" h="20"/>""" +
                        """<layer id="title" x="0" y="60" w="200" h="20" dblclickaction="switch;shade"/>""" +
                        """</layout><layout id="shade" w="200" h="14"/></container>""",
                ).mapValues { it.value.toByteArray() }

            override fun read(path: String): ByteArray? = m[SkinFiles.normalize(path)]

            override fun names(): List<String> = m.keys.toList()
        }

    private val player = FakePlayer()
    private val engines = ArrayList<WalEngine>()

    /** An engine on the skin, reading [idle], closed after the test; [report] holds what its start said. */
    private inner class Run(
        label: String,
        idle: (SkinRuntime) -> WalIdle = WalIdle::of,
    ) {
        // a closed engine's thread may still be ending when the next test starts one
        val name = "$label-${runs.incrementAndGet()}"
        val host = WalHost(files, app.cacheDir)
        val frames = AtomicInteger()
        val engine = WalEngine(name, host, idle) { frames.incrementAndGet() }.also { engines += it }
        val report = CompletableFuture<Throwable?>()

        /** The engine thread's looper, found by the thread's name. */
        val looper: Looper by lazy { ShadowLooper.getAllLoopers().single { it.thread.name == "wal-$name" } }

        fun load() = SkinRuntime(files, host, player).apply { load() }

        fun start(build: () -> SkinRuntime = ::load) = engine.start(onStart = { report.complete(it) }, build = build)

        fun failure(): Throwable? = report.get(5, TimeUnit.SECONDS)
    }

    private val main = Run("rest").apply { start() }
    private val engine = main.engine

    @After
    fun close() = engines.forEach { it.close() }

    /** Waits up to 5 s for [done]. */
    private fun awaitThat(
        what: String,
        done: () -> Boolean,
    ) {
        repeat(500) {
            if (done()) return
            Thread.sleep(10)
        }
        fail(what)
    }

    /** Waits until the work posted to the main run's engine before this is done. */
    private fun onEngine(read: (SkinRuntime) -> Unit) {
        val got = CompletableFuture<Unit>()
        engine.post(publish = false) { got.complete(read(it)) }
        got.get(5, TimeUnit.SECONDS)
    }

    /** Opens a menu from a touch on [on]'s thread; the future holds what the script heard. */
    private fun openMenu(on: WalEngine): CompletableFuture<Int> {
        val heard = CompletableFuture<Int>()
        on.post { rt ->
            val menu = PopupMenu(rt).apply { makiCall("addcommand", listOf(Value.Str("Item"), Value.Int(1))) }
            heard.complete(rt.gesture(fresh = true) { menu.makiCall("popatmouse", emptyList())!!.asInt })
        }
        return heard
    }

    private fun awaitMenu(on: WalEngine): SkinMenu {
        awaitThat("the menu shows") { on.menu != null }
        return on.menu!!
    }

    /**
     * A running skin timer of [periodMs], and an idle source that reports when it is next due, with
     * the music [stopped] or not. Every [changesEvery]th time it fires it changes what the skin
     * shows, which the next tick finds.
     */
    private class SkinTimer(
        private val periodMs: Int,
        private val stopped: Boolean = false,
        private val changesEvery: Long = 1,
    ) {
        /** How often it fired, as the engine thread saw last. */
        @Volatile
        var fired = 0L

        fun startOn(rt: SkinRuntime) {
            Timer(rt).apply {
                delay = periodMs
                start()
            }
        }

        fun idle(rt: SkinRuntime): WalIdle {
            if (rt.stats.timerFires != fired) {
                fired = rt.stats.timerFires
                if (fired % changesEvery == 0L) rt.invalidate()
            }
            return WalIdle(framesWanted = false, nextDueAt = rt.timers.minOfOrNull { it.nextAt }, stopped = stopped)
        }
    }

    /**
     * An engine on a skin whose idle source is [idle], on a runtime clock that is the engine's;
     * [setUp] readies the runtime once it has loaded.
     */
    private fun resting(
        label: String,
        setUp: (SkinRuntime) -> Unit = {},
        idle: (SkinRuntime) -> WalIdle,
    ): Run {
        val run = Run(label, idle = idle)
        run.start {
            run.load().apply {
                clock = SystemClock::uptimeMillis
                setUp(this)
            }
        }
        assertNull(run.failure())
        return run
    }

    /** An engine on a skin that runs [timer] and wants no frames. */
    private fun resting(
        label: String,
        timer: SkinTimer,
    ): Run = resting(label, timer::startOn, timer::idle)

    /**
     * Waits until [run]'s engine thread has done what was posted and fell due before now, and the
     * wakes that work set for now: Robolectric runs the messages due on that thread until none is
     * left. A task the test posts cannot tell this, as each task sets the wake again after its body
     * has run.
     */
    private fun settle(run: Run) = shadowOf(run.looper).idle()

    /**
     * Whether [run]'s engine has a wake set, read on its thread between two tasks: each task sets
     * the wake again behind itself, which another thread can see half done.
     */
    private fun wakeSet(run: Run): Boolean {
        val set = CompletableFuture<Boolean>()
        run.engine.postAlways { set.complete(run.engine.wakeSet) }
        return set.get(5, TimeUnit.SECONDS)
    }

    /** Moves the clock on by [ms], one at a time, letting [run]'s engine thread catch up with each. */
    private fun advance(
        run: Run,
        ms: Long,
    ) = repeat(ms.toInt()) {
        ShadowSystemClock.advanceBy(Duration.ofMillis(1))
        settle(run)
    }

    @Test(timeout = 20_000)
    fun `an awake skin that wants no frames wakes itself when its next timer is due`() {
        val timer = SkinTimer(periodMs = 100)
        val run = resting("due", timer)
        run.engine.awake = true

        advance(run, 99)
        assertEquals("not before the timer is due", 0, run.engine.wakes)
        advance(run, 1)
        assertEquals("as it falls due", 1, run.engine.wakes)
        advance(run, 100)
        assertEquals("and as it falls due again", 2, run.engine.wakes)
        assertEquals(2L, timer.fired)
    }

    @Test(timeout = 20_000)
    fun `it wakes at most four times a second however soon things are due`() {
        val run = resting("soon") { WalIdle(framesWanted = false, nextDueAt = it.now(), stopped = true) }
        run.engine.awake = true

        advance(run, 1_000)

        assertTrue("${run.engine.wakes} wakes in a second", run.engine.wakes in 4..5)
    }

    @Test(timeout = 20_000)
    fun `a playing skin's 17 ms timer fires on time without a frame loop`() {
        val timer = SkinTimer(periodMs = 17)
        val run = resting("beat", timer)
        run.engine.awake = true

        advance(run, 1_700)

        assertTrue("${timer.fired} fires in 1.7 s", timer.fired in 99L..100L)
        assertEquals("woken for each", timer.fired, run.engine.wakes.toLong())
        assertEquals("the frames say so", false, run.engine.frame?.wantsFrames)
    }

    @Test(timeout = 20_000)
    fun `a paused skin's 50 ms blink is not capped`() {
        // a pause blink: its timer runs every 50 ms, and the time shows or hides every tenth fire
        val timer = SkinTimer(periodMs = 50, changesEvery = 10)
        val run = resting("blink", timer)
        run.engine.awake = true

        advance(run, 2_000)

        assertTrue("${timer.fired} fires in 2 s", timer.fired in 39L..40L)
    }

    @Test(timeout = 20_000)
    fun `a timer that changes nothing for a second is woken four times a second at most`() {
        val timer = SkinTimer(periodMs = 17, changesEvery = Long.MAX_VALUE)
        val run = resting("idle timer", timer)
        run.engine.awake = true

        advance(run, 1_000)
        val static = timer.fired
        advance(run, 1_000)

        assertTrue("about one fire a frame at first: $static", static >= 50)
        assertTrue("four a second once static: ${timer.fired - static}", timer.fired - static in 3L..5L)
    }

    @Test(timeout = 20_000)
    fun `a stopped skin's timer steps four times a second`() {
        val timer = SkinTimer(periodMs = 17, stopped = true)
        val run = resting("stopped", timer)
        run.engine.awake = true

        advance(run, 1_000)

        assertTrue("${timer.fired} fires in a second", timer.fired in 3L..5L)
    }

    @Test(timeout = 20_000)
    fun `a resting engine wakes for nothing and drops the wake it had`() {
        val timer = SkinTimer(periodMs = 100)
        val run = resting("rests", timer)
        run.engine.awake = true
        settle(run)
        assertTrue("awake, a wake is set", wakeSet(run))

        run.engine.awake = false

        assertFalse("resting, it is dropped", wakeSet(run))
        advance(run, 500)
        assertEquals(0, run.engine.wakes)
        assertFalse("and none is set again", wakeSet(run))

        run.engine.awake = true
        advance(run, 1)
        assertEquals("woken again, the timer due meanwhile wakes it once", 1, run.engine.wakes)
    }

    @Test(timeout = 20_000)
    fun `a menu up drops the wake`() {
        val timer = SkinTimer(periodMs = 100)
        val run = resting("menu", timer)
        run.engine.awake = true
        settle(run)
        assertTrue(wakeSet(run))

        val heard = openMenu(run.engine)
        val menu = awaitMenu(run.engine)

        assertFalse("no wake waits under the menu", run.engine.wakeSet)
        ShadowSystemClock.advanceBy(Duration.ofMillis(300))
        run.engine.answerMenu(menu, 1)
        assertEquals(1, heard.get(5, TimeUnit.SECONDS))
        settle(run)
        assertTrue("what fell due under it wakes the skin once after: ${run.engine.wakes}", run.engine.wakes <= 1)
        advance(run, 1)
        assertTrue("and the wakes go on", wakeSet(run))
    }

    @Test(timeout = 20_000)
    fun `a change in wanting frames is published by itself`() {
        val wants = AtomicBoolean(true)
        val run = Run("wants", idle = { WalIdle(framesWanted = wants.get(), nextDueAt = null) })
        run.start { run.load().apply { clock = SystemClock::uptimeMillis } }
        assertNull(run.failure())
        // the first tick takes in the song's title, which is a change too
        run.engine.requestTick()
        settle(run)
        assertEquals(true, run.engine.frame?.wantsFrames)
        val frames = run.frames.get()

        wants.set(false)
        run.engine.requestTick()
        settle(run)
        assertEquals("a frame for that alone", frames + 1, run.frames.get())
        assertEquals(false, run.engine.frame?.wantsFrames)
        run.engine.requestTick()
        settle(run)
        assertEquals("none for a tick that changes nothing", frames + 1, run.frames.get())

        wants.set(true)
        run.engine.requestTick()
        settle(run)
        assertEquals(true, run.engine.frame?.wantsFrames)
        ShadowSystemClock.advanceBy(Duration.ofMillis(WalRest.STATIC_AFTER_MS))
        run.engine.requestTick()
        settle(run)
        assertEquals("a skin whose frames changed nothing for a second goes static", false, run.engine.frame?.wantsFrames)
        assertEquals(frames + 3, run.frames.get())
    }

    @Test(timeout = 20_000)
    fun `a pointer move and a tick waiting together publish one frame`() {
        engine.requestTick()
        onEngine { }
        val before = main.frames.get()
        val holding = CountDownLatch(1)
        engine.post(publish = false) { holding.await() }

        // the tick finds a new song, which it would publish by itself
        player.title = "Another song"
        engine.pointerMove(15, 15)
        engine.requestTick()
        holding.countDown()
        onEngine { }

        assertEquals(before + 1, main.frames.get())
        player.title = "A third song"
        engine.requestTick()
        onEngine { }
        assertEquals("a new song alone is a frame", before + 2, main.frames.get())
    }

    @Test(timeout = 20_000)
    fun `a change the skin shows while a tick runs is ticked again after it`() {
        val hold = AtomicBoolean(false)
        val inTick = CountDownLatch(1)
        val release = CountDownLatch(1)
        val run = resting("again") { WalIdle(framesWanted = false, nextDueAt = null, stopped = true) }
        // called at the end of a tick, which has read the player by then
        run.engine.onTicked = { _, _, _, _, _ ->
            if (hold.getAndSet(false)) {
                inTick.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
        }
        run.engine.awake = true
        run.engine.requestTick()
        settle(run)
        val frames = run.frames.get()

        hold.set(true)
        run.engine.requestTick(WalCause.KEY)
        assertTrue("the tick runs", inTick.await(5, TimeUnit.SECONDS))
        player.title = "Another song"
        run.engine.requestTick(WalCause.KEY)
        release.countDown()
        settle(run)

        assertEquals("the new song is shown, with nothing else to tick the resting skin", frames + 1, run.frames.get())
        assertFalse("and nothing is set to wake it", wakeSet(run))
    }

    @Test(timeout = 20_000)
    fun `a press and its release waiting together publish one frame`() {
        engine.requestTick()
        onEngine { }
        val before = main.frames.get()
        val holding = CountDownLatch(1)
        engine.post(publish = false) { holding.await() }

        engine.pointerDown(100, 70)
        engine.pointerUp(100, 70)
        holding.countDown()
        onEngine { }

        assertEquals(before + 1, main.frames.get())
        engine.pointerDown(100, 70)
        onEngine { }
        engine.pointerUp(100, 70)
        onEngine { }
        assertEquals("one each when they come apart", before + 3, main.frames.get())
    }

    @Test(timeout = 20_000)
    fun `a log line alone publishes nothing`() {
        engine.requestTick()
        onEngine { }
        val before = main.frames.get()

        engine.post(publish = false) { main.host.log("a line from the skin") }
        engine.requestTick()
        onEngine { }

        assertEquals(before, main.frames.get())
    }

    private companion object {
        val runs = AtomicInteger()
    }
}
