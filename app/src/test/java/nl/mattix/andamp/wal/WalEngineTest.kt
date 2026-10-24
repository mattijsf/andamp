// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.wal

import android.app.Application
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.test.core.app.ApplicationProvider
import nl.mattix.andamp.modernskin.maki.Value
import nl.mattix.andamp.modernskin.skin.FakePlayer
import nl.mattix.andamp.modernskin.skin.Player
import nl.mattix.andamp.modernskin.skin.PopupMenu
import nl.mattix.andamp.modernskin.skin.SkinFiles
import nl.mattix.andamp.modernskin.skin.SkinMenu
import nl.mattix.andamp.modernskin.skin.SkinRuntime
import nl.mattix.andamp.ui.overlay.OverlayBounds
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

/**
 * The engine thread outlives whatever a skin does: a skin that does not load is reported, failures
 * in a row break it once, and while its menu is up it stands still. A fit places the stack in the
 * player's box.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WalEngineTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    private fun skin(vararg entries: Pair<String, String>): SkinFiles =
        object : SkinFiles {
            private val m = entries.toMap().mapValues { it.value.toByteArray() }

            override fun read(path: String): ByteArray? = m[SkinFiles.normalize(path)]

            override fun names(): List<String> = m.keys.toList()
        }

    private fun skinXml(player: String) =
        arrayOf(
            "skin.xml" to """<WinampAbstractionLayer version="0.8"><include file="player.xml"/></WinampAbstractionLayer>""",
            "player.xml" to player,
        )

    // a play button, and a title bar whose double click switches to the shade
    private val files =
        skin(
            *skinXml(
                """<container id="main" name="t" default_visible="1"><layout id="normal" w="200" h="100">""" +
                    """<button id="play" action="PLAY" x="10" y="10" w="20" h="20"/>""" +
                    """<layer id="title" x="0" y="60" w="200" h="20" dblclickaction="switch;shade"/>""" +
                    """</layout><layout id="shade" w="200" h="14"/></container>""",
            ),
        )

    private val player = FakePlayer()
    private val engines = ArrayList<WalEngine>()

    /**
     * An engine for [skin] on a thread named after [label], closed after the test; [report] holds
     * what its start said.
     */
    private inner class Run(
        label: String,
        val skin: SkinFiles = files,
        idle: (SkinRuntime) -> WalIdle = WalIdle::of,
        onFrame: () -> Unit = {},
    ) {
        // a closed engine's thread may still be ending when the next test starts one
        val name = "$label-${runs.incrementAndGet()}"
        val host = WalHost(skin, app.cacheDir)
        val frames = AtomicInteger()
        val engine =
            WalEngine(name, host, idle) {
                frames.incrementAndGet()
                onFrame()
            }.also { engines += it }
        val report = CompletableFuture<Throwable?>()

        fun load() = SkinRuntime(skin, host, player).apply { load() }

        fun start(
            budgetMs: Long = WalBuild.BUDGET_MS,
            box: IntSize? = null,
            build: () -> SkinRuntime = ::load,
        ) = engine.start(budgetMs, box, onStart = { report.complete(it) }, build = build)

        fun failure(): Throwable? = report.get(5, TimeUnit.SECONDS)
    }

    private val main = Run("test").apply { start() }
    private val engine = main.engine
    private val ticks = AtomicInteger()

    init {
        engine.onTicked = { _, _, _, _, _ -> ticks.incrementAndGet() }
    }

    @After
    fun close() = engines.forEach { it.close() }

    /** Opens a menu from a touch on the engine thread; the future holds what the script heard. */
    private fun openMenu(): CompletableFuture<Int> {
        val heard = CompletableFuture<Int>()
        engine.post { rt ->
            val menu = PopupMenu(rt).apply { makiCall("addcommand", listOf(Value.Str("Item"), Value.Int(1))) }
            heard.complete(rt.gesture(fresh = true) { menu.makiCall("popatmouse", emptyList())!!.asInt })
        }
        return heard
    }

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

    private fun awaitMenu(): SkinMenu {
        awaitThat("the menu shows") { engine.menu != null }
        return engine.menu!!
    }

    /** What [read] gives on the engine thread, once the work posted before it is done. */
    private fun <T> onEngine(read: (SkinRuntime) -> T): T {
        val got = CompletableFuture<T>()
        engine.post(publish = false) { got.complete(read(it)) }
        return got.get(5, TimeUnit.SECONDS)
    }

    private fun threadOf(run: Run): Thread = Thread.getAllStackTraces().keys.single { it.name == "wal-${run.name}" }

    /** The engine thread of [run] is still there a moment after its failure. */
    private fun assertRunsOn(run: Run) {
        val thread = threadOf(run)
        thread.join(200)
        assertTrue("the engine thread runs on", thread.isAlive)
    }

    @Test(timeout = 20_000)
    fun `a skin that loads says so and shows its first frame`() {
        assertNull(main.failure())
        assertEquals(200 to 100, engine.frame?.mainSize)
    }

    @Test(timeout = 20_000)
    fun `a build that throws fails to start, and the engine thread runs on`() {
        val run = Run("throws")
        run.start { error("not a skin") }

        assertEquals("not a skin", run.failure()?.message)
        assertRunsOn(run)
        assertNull(run.engine.frame)
    }

    @Test(timeout = 20_000)
    fun `a skin with no main window fails to start, and the engine thread runs on`() {
        val run = Run("blank", skin("skin.xml" to """<WinampAbstractionLayer version="0.8"/>"""))
        run.start()

        assertEquals("no main layout", run.failure()?.message)
        assertRunsOn(run)
        assertNull(run.engine.frame)
    }

    @Test(timeout = 20_000)
    fun `a skin larger than a phone can draw fails to start`() {
        // FuzzTest's huge sizes
        val huge =
            skin(
                *skinXml(
                    """<container id="main" name="t" default_visible="1"><layout id="normal" w="2000000000" h="2000000000">""" +
                        """<layer x="0" y="0" w="1000000" h="1000000"/></layout></container>""",
                ),
            )
        val run = Run("huge", huge)
        run.start()

        assertTrue(
            run
                .failure()
                ?.message
                .orEmpty()
                .startsWith("too large"),
        )
        assertRunsOn(run)
    }

    @Test(timeout = 20_000)
    fun `a skin that fits its box only below the smallest scale fails to start`() {
        val run = Run("tiny")
        // 200 x 100 into 40 x 20 is a scale of 0.2
        run.start(box = IntSize(40, 20))

        assertTrue(
            run
                .failure()
                ?.message
                .orEmpty()
                .startsWith("too large for 40x20"),
        )
    }

    @Test(timeout = 20_000)
    fun `a load still running when its time is up fails then, and what it loads after is dropped`() {
        val loading = CountDownLatch(1)
        val run = Run("overrun")
        run.start(budgetMs = 100) {
            loading.await()
            run.load()
        }

        ShadowLooper.idleMainLooper(150, TimeUnit.MILLISECONDS)
        assertTrue(run.failure() is TimeoutException)

        loading.countDown()
        assertRunsOn(run)
        assertNull("the late skin is not shown", run.engine.frame)
        assertEquals(0, run.frames.get())
    }

    @Test(timeout = 20_000)
    fun `a load that took longer than its time fails, also when the engine notices first`() {
        val run = Run("slow")
        run.start(budgetMs = 100) {
            Thread.sleep(300)
            run.load()
        }

        assertTrue(run.failure() is TimeoutException)
        assertRunsOn(run)
        assertNull(run.engine.frame)
    }

    @Test(timeout = 20_000)
    fun `a start that ends while the engine closes reports nothing`() {
        val drawing = CountDownLatch(1)
        val closedNow = CountDownLatch(1)
        // the first frame is being handed over when the engine closes
        val run =
            Run("closing", onFrame = {
                drawing.countDown()
                closedNow.await()
            })
        run.start()
        assertTrue(drawing.await(5, TimeUnit.SECONDS))
        val thread = threadOf(run)

        run.engine.close()
        closedNow.countDown()

        thread.join(5_000)
        assertFalse("the engine thread ended", thread.isAlive)
        assertFalse("no start report after close", run.report.isDone)
    }

    @Test(timeout = 20_000)
    fun `work that fails after close breaks nothing`() {
        val broken = AtomicInteger()
        engine.onBroken = { broken.incrementAndGet() }
        val holding = CountDownLatch(1)
        engine.post { holding.await() }
        repeat(WalEngine.MAX_FAILURES) { engine.post { error("script error") } }
        val thread = threadOf(main)

        engine.close()
        holding.countDown()

        thread.join(5_000)
        assertFalse("the engine thread ended", thread.isAlive)
        assertEquals(0, broken.get())
    }

    @Test(timeout = 20_000)
    fun `postAlways runs also when the skin did not start`() {
        val run = Run("unstarted")
        run.start { error("not a skin") }
        run.failure()

        val skipped = CompletableFuture<Unit>()
        run.engine.post { skipped.complete(Unit) }
        val ran = CompletableFuture<SkinRuntime?>()
        run.engine.postAlways { ran.complete(it) }

        assertNull("it runs, with no skin", ran.get(5, TimeUnit.SECONDS))
        assertFalse("post runs nothing with no skin", skipped.isDone)
        val withSkin = CompletableFuture<SkinRuntime?>()
        engine.postAlways { withSkin.complete(it) }
        assertEquals("with a skin on, it gets that skin", 200 to 100, withSkin.get(5, TimeUnit.SECONDS)?.mainLayout?.size)
    }

    @Test(timeout = 20_000)
    fun `three failures in a row break the skin once, and one that works in between starts the count over`() {
        val broken = AtomicInteger()
        engine.onBroken = { broken.incrementAndGet() }

        fun postFailure() = engine.post { error("script error") }

        // a failing task that first reads how often the skin broke
        fun brokenBeforeFailing(): Int {
            val seen = CompletableFuture<Int>()
            engine.post {
                seen.complete(broken.get())
                error("script error")
            }
            return seen.get(5, TimeUnit.SECONDS)
        }

        postFailure()
        postFailure()
        engine.post { }
        postFailure()
        assertEquals("after a task that worked, one failure, then this second", 0, brokenBeforeFailing())
        assertEquals("the third in a row is this one", 0, brokenBeforeFailing())
        awaitThat("the third failure in a row breaks it") { broken.get() == 1 }

        postFailure()
        postFailure()
        assertEquals(1, brokenBeforeFailing())
        assertEquals("broken once", 1, onEngine { broken.get() })
    }

    @Test(timeout = 20_000)
    fun `a tick report that keeps throwing breaks the skin once, and the engine runs on`() {
        val broken = AtomicInteger()
        val reported = AtomicInteger()
        engine.onBroken = { broken.incrementAndGet() }
        engine.onTicked = { _, _, _, _, _ ->
            reported.incrementAndGet()
            error("report failed")
        }

        for (n in 1..5) {
            // asked again until it runs: the one before may still be finishing
            awaitThat("tick $n runs") {
                engine.requestTick()
                reported.get() >= n
            }
        }

        assertEquals(1, onEngine { broken.get() })
        assertRunsOn(main)
    }

    @Test(timeout = 20_000)
    fun `a skin that grows too large to draw keeps its last frame, and breaks`() {
        val broken = AtomicInteger()
        engine.onBroken = { broken.incrementAndGet() }

        repeat(WalEngine.MAX_FAILURES) {
            engine.post { rt ->
                rt.mainLayout!!.makiCall("resize", listOf(Value.Int(0), Value.Int(0), Value.Int(10_000), Value.Int(100)))
            }
        }

        assertEquals(1, onEngine { broken.get() })
        assertEquals("the last frame stays", 200 to 100, engine.frame?.mainSize)
    }

    @Test(timeout = 20_000)
    fun `a canceled press on the play button does not play`() {
        engine.pointerDown(20, 20)
        engine.pointerCancel()
        engine.pointerUp(20, 20)
        assertEquals(Player.Status.STOPPED, onEngine { player.status })

        engine.pointerDown(20, 20)
        engine.pointerUp(20, 20)
        assertEquals("a press that is not canceled plays", Player.Status.PLAYING, onEngine { player.status })
    }

    @Test(timeout = 20_000)
    fun `a double tap on the title bar switches to the shade`() {
        engine.doubleTap(100, 70)

        assertEquals("shade", onEngine { it.mainLayout?.id })
        awaitThat("the shade's frame is published") { engine.frame?.mainSize == (200 to 14) }
    }

    @Test(timeout = 20_000)
    fun `ticks asked for under a menu do not run after it`() {
        val heard = openMenu()
        val menu = awaitMenu()
        val before = ticks.get()

        repeat(5) { engine.requestTick() }
        engine.answerMenu(menu, 1)

        assertEquals(1, heard.get(5, TimeUnit.SECONDS))
        // work posted now runs after anything the menu left queued
        val after = CompletableFuture<Unit>()
        engine.post { after.complete(Unit) }
        after.get(5, TimeUnit.SECONDS)
        assertEquals("none of the five ran in a burst after the menu", before, ticks.get())
        engine.requestTick()
        awaitThat("a tick asked for after the menu runs") { ticks.get() == before + 1 }
    }

    private val phone = WalFit(1080, 2000, 3f, sideways = false)

    @Test(timeout = 20_000)
    fun `a fit gives the skin the box, and the next frame a layout for it`() {
        assertNull(main.failure())

        engine.fit(phone)

        awaitThat("a frame with a layout") { engine.frame?.layout != null }
        val frame = engine.frame!!
        val layout = frame.layout!!
        val scale = WalScale.fit(IntSize(1080, 2000), frame.mainSize!!, frame.baseW)
        assertEquals(phone, layout.fit)
        assertEquals(scale, layout.scale)
        assertEquals(WalScale.left(1080f, frame.contentRight, scale), layout.left)
        assertEquals(0f, layout.top)
        assertEquals(200 to 100, layout.stackW to layout.stackH)
        assertEquals((1080 / scale).toInt() to (2000 / scale).toInt(), onEngine { it.viewSize })
        assertEquals("24 dp at that scale", kotlin.math.ceil(24 * 3 / scale).toInt(), onEngine { it.touchSlop })
    }

    @Test(timeout = 20_000)
    fun `a compact fit keeps every window at its natural height, and a full one stretches the last again`() {
        engine.fit(phone.copy(compact = true))
        awaitThat("a frame for the compact box") {
            engine.frame
                ?.layout
                ?.fit
                ?.compact == true
        }
        assertFalse(onEngine { it.stretchLast })

        engine.fit(phone)
        awaitThat("a frame for the full box") { engine.frame?.layout?.fit == phone }
        assertTrue(onEngine { it.stretchLast })
    }

    @Test(timeout = 20_000)
    fun `a window that resizes both ways fills the box, and in a compact box keeps its smallest height`() {
        val run =
            Run(
                "resizes",
                skin(
                    *skinXml(
                        """<container id="main" name="t" default_visible="1">""" +
                            """<layout id="normal" w="400" h="300" minimum_w="200" minimum_h="100">""" +
                            """<layer id="grip" x="380" y="280" w="20" h="20" resize="bottomright"/>""" +
                            """</layout></container>""",
                    ),
                ),
            )
        run.start()
        assertNull(run.failure())
        val scale = WalScale.of(IntSize(1080, 2000), 200, 100)

        run.engine.fit(phone)
        awaitThat("a frame for the full box") {
            run.engine.frame
                ?.layout
                ?.fit == phone
        }
        val full = run.engine.frame!!.layout!!
        assertEquals((1080 / scale).toInt() to (2000 / scale).toInt(), full.stackW to full.stackH)

        run.engine.fit(phone.copy(compact = true))
        awaitThat("a frame for the compact box") {
            run.engine.frame
                ?.layout
                ?.fit
                ?.compact == true
        }
        val compact = run.engine.frame!!.layout!!
        assertEquals("as wide, no taller than its minimum", full.stackW to 100, compact.stackW to compact.stackH)
    }

    @Test(timeout = 20_000)
    fun `a window's snap margin is left out of the layout's stack, which sideways is centered by what shows`() {
        // a main window 100 px high that shows 60 px: the 40 px under its shaped body is a snap margin
        val run =
            Run(
                "snap",
                skin(
                    *skinXml(
                        """<container id="main" name="t" default_visible="1">""" +
                            """<layout id="normal" w="200" h="100" snapadjustbottom="40">""" +
                            """<group id="body" x="0" y="0" w="200" h="60" sysregion="1"/>""" +
                            """</layout></container>""",
                    ),
                ),
            )
        run.start()
        assertNull(run.failure())

        run.engine.fit(phone)
        awaitThat("a frame for the phone") {
            run.engine.frame
                ?.layout
                ?.fit == phone
        }
        val upright = run.engine.frame!!
        assertEquals(
            "the window is drawn whole",
            100,
            upright.stack
                .single()
                .first.height,
        )
        assertEquals(200 to 60, upright.layout!!.stackW to upright.layout!!.stackH)
        assertEquals(0f, upright.layout!!.top)

        val sideways = WalFit(2000, 1000, 3f, sideways = true)
        run.engine.fit(sideways)
        awaitThat("a frame for the sideways box") {
            run.engine.frame
                ?.layout
                ?.fit == sideways
        }
        val layout = run.engine.frame!!.layout!!
        assertEquals("the scale keeps room for the whole window", 10f, layout.scale)
        assertEquals("centered by the 60 px that show", 200f, layout.top)
        assertEquals(200 to 60, layout.stackW to layout.stackH)
    }

    @Test(timeout = 20_000)
    fun `a window's room on its right that shows nothing is left out of the stack, its window rect and touch window`() {
        // a main window 300 px wide whose shaped body ends at 200: the 100 px beside it keep room for a drawer
        val run =
            Run(
                "room",
                skin(
                    *skinXml(
                        """<container id="main" name="t" default_visible="1"><layout id="normal" w="300" h="100">""" +
                            """<group id="body" x="0" y="0" w="200" h="100" sysregion="1" rectrgn="1"/>""" +
                            """</layout></container>""",
                    ),
                ),
            )
        run.start()
        assertNull(run.failure())

        // the floating player's box
        val floating = phone.copy(compact = true)
        run.engine.fit(floating)
        awaitThat("a frame for the floating box") {
            run.engine.frame
                ?.layout
                ?.fit == floating
        }
        val frame = run.engine.frame!!
        assertEquals(
            "the window is drawn whole",
            300,
            frame.stack
                .single()
                .first.width,
        )
        val layout = frame.layout!!
        assertEquals("a whole scale from the 200 px that show", 5f, layout.scale)
        assertEquals(200 to 100, layout.stackW to layout.stackH)
        assertEquals("centered by the 200 px that show", 40f, layout.left)

        val placed = WalPlacement.of(IntRect(0, 0, 1080, 2000), layout, scale = 3)
        assertEquals(IntRect(40, 0, 1040, 500), placed.stack)
        assertEquals(IntRect(13, 0, 347, 167), placed.window)
        assertEquals(
            "the touch window ends where the skin does, inside the screen",
            IntRect(39, 0, 1041, 501),
            OverlayBounds.windowFor(listOf(placed.window), scale = 3, originX = 0, originY = 0),
        )

        val sideways = WalFit(2000, 1000, 3f, sideways = true)
        run.engine.fit(sideways)
        awaitThat("a frame for the sideways box") {
            run.engine.frame
                ?.layout
                ?.fit == sideways
        }
        val turned = run.engine.frame!!.layout!!
        assertEquals("the scale keeps room for the whole window", 6f, turned.scale)
        assertEquals("centered by the 200 px that show", 400f, turned.left)
        assertEquals(200 to 100, turned.stackW to turned.stackH)
    }

    @Test(timeout = 20_000)
    fun `a fit equal to the last one does nothing`() {
        engine.fit(phone)
        awaitThat("a frame with a layout") { engine.frame?.layout != null }
        onEngine { }
        val before = main.frames.get()

        engine.fit(phone.copy())

        onEngine { }
        assertEquals("no new frame", before, main.frames.get())
    }

    @Test(timeout = 20_000)
    fun `a box asked for before the skin loads is in its first frame`() {
        val run = Run("early")
        run.engine.fit(phone)

        run.start()

        assertNull(run.failure())
        assertEquals(
            phone,
            run.engine.frame
                ?.layout
                ?.fit,
        )
        assertEquals("one frame, already fitted", 1, run.frames.get())
    }

    @Test(timeout = 20_000)
    fun `failed draws break the skin at three in a row, counted apart from the tasks`() {
        val broken = AtomicInteger()
        engine.onBroken = { broken.incrementAndGet() }

        fun drawFails() = engine.drawFailed(IllegalStateException("draw"))

        drawFails()
        drawFails()
        engine.drawWorked()
        drawFails()
        drawFails()
        assertEquals("a frame drawn in between starts the count over", 0, onEngine { broken.get() })

        // a task that completes does not clear failed draws
        engine.post { }
        drawFails()
        awaitThat("the third failed draw in a row breaks it") { broken.get() == 1 }
        drawFails()
        repeat(WalEngine.MAX_FAILURES) { engine.post { error("script error") } }
        assertEquals("broken once", 1, onEngine { broken.get() })
    }

    @Test(timeout = 20_000)
    fun `a frame carries the color theme the skin wears`() {
        val run =
            Run(
                "themes",
                skin(
                    *skinXml(
                        """<gammaset id="One"/><gammaset id="Two"/>""" +
                            """<container id="main" name="t" default_visible="1">""" +
                            """<layout id="normal" w="200" h="100"/></container>""",
                    ),
                ),
            )
        run.start()
        assertNull(run.failure())
        assertEquals("One", run.engine.frame?.themeKey)

        run.engine.post { it.selectGammaSet("two") }

        awaitThat("a frame in the other theme") { run.engine.frame?.themeKey == "Two" }
        val worn = CompletableFuture<String?>()
        run.engine.post(publish = false) { worn.complete(it.currentGammaSet) }
        assertEquals(worn.get(5, TimeUnit.SECONDS), run.engine.frame?.themeKey)
    }

    @Test(timeout = 20_000)
    fun `a frame and its layout say whether the skin wants frames, as the idle source tells`() {
        val asked = AtomicInteger()
        val run =
            Run("idle", idle = { rt ->
                asked.incrementAndGet()
                WalIdle(framesWanted = false, nextDueAt = rt.now() + 100)
            })
        run.engine.fit(phone)
        run.start()
        assertNull(run.failure())

        assertEquals(false, run.engine.frame?.wantsFrames)
        assertEquals(
            false,
            run.engine.frame
                ?.layout
                ?.wantsFrames,
        )
        assertTrue("asked as the frame was made", asked.get() > 0)
        engine.fit(phone)
        awaitThat("the runtime, as the source, says it wants frames") { engine.frame?.layout?.wantsFrames == true }
        assertEquals(true, engine.frame?.wantsFrames)
    }

    @Test(timeout = 20_000)
    fun `every frame published and every tick is logged with what caused it`() {
        assertNull(main.failure())
        WalTiming.on = true
        ShadowLog.clear()

        engine.pointerDown(15, 15)
        engine.pointerUp(15, 15)
        onEngine { }
        engine.requestTick(WalCause.KEY)
        onEngine { }
        engine.fit(phone)
        onEngine { }

        val line = Regex("tick wal-${main.name}: tick \\d+ ns, publish \\d+ ns, (\\w+)")
        val causes = ShadowLog.getLogsForTag(WalTiming.TAG).mapNotNull { line.matchEntire(it.msg)?.groupValues?.get(1) }
        assertEquals(listOf("touch", "touch", "key", "fit"), causes)
    }

    @Test(timeout = 20_000)
    fun `closing the engine frees a menu that waits`() {
        val heard = openMenu()
        awaitMenu()

        engine.close()

        assertEquals("the script hears nothing picked", 0, heard.get(5, TimeUnit.SECONDS))
    }

    private companion object {
        val runs = AtomicInteger()
    }
}
