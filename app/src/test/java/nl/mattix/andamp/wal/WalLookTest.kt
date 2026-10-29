// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.wal

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.FrameLayout
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.GraphicsContext
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.core.app.ApplicationProvider
import nl.mattix.andamp.modernskin.maki.Value
import nl.mattix.andamp.modernskin.skin.FakePlayer
import nl.mattix.andamp.modernskin.skin.Player
import nl.mattix.andamp.modernskin.skin.SkinMenu
import nl.mattix.andamp.modernskin.skin.SkinRuntime
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowSystemClock
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * A worn modern skin's look: it starts on its first fit or on an import's wait, hands the screen
 * its frames through one main post, keeps its settings, and lets go of its engine without a save
 * landing afterward.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WalLookTest {
    @get:Rule
    val dir = TemporaryFolder()

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val prefs = app.getSharedPreferences("wal_skins", Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())
    private val looks = ArrayList<WalLook>()

    @Before
    fun clearSettings() {
        prefs.edit().clear().commit()
    }

    @After
    fun releaseAll() = looks.forEach { it.release(save = false) }

    /** A play button, a minimize button the app answers, and two color themes. */
    private val player =
        """<gammaset id="One"/><gammaset id="Two"/>""" +
            """<container id="main" name="t" default_visible="1"><layout id="normal" w="200" h="100">""" +
            """<button id="play" action="PLAY" x="10" y="10" w="20" h="20"/>""" +
            """<button id="min" action="MINIMIZE" x="100" y="10" w="20" h="20"/>""" +
            """</layout></container>"""

    private fun wal(
        skinXml: String = """<WinampAbstractionLayer version="0.8"><include file="player.xml"/></WinampAbstractionLayer>""",
        playerXml: String = player,
    ): File =
        dir.newFile().apply {
            ZipOutputStream(outputStream()).use { zip ->
                for ((path, text) in mapOf("skin.xml" to skinXml, "player.xml" to playerXml)) {
                    zip.putNextEntry(ZipEntry(path))
                    zip.write(text.toByteArray())
                    zip.closeEntry()
                }
            }
        }

    /** Counts the main posts the look makes. */
    private val posts = AtomicInteger()

    private fun look(
        file: File = wal(),
        name: String = "Test",
        player: Player = FakePlayer(),
        budgetMs: Long = 5_000,
        siblings: Map<String, File> = emptyMap(),
    ): WalLook =
        WalLook(
            id = "id${runs.incrementAndGet()}",
            file = file,
            name = name,
            siblings = siblings,
            cacheDir = app.cacheDir,
            settings = WalSettings(app),
            player = player,
            budgetMs = budgetMs,
            toMain = { r ->
                posts.incrementAndGet()
                main.post(r)
            },
        ).also { looks += it }

    private val phone = WalFit(1080, 2000, 3f, sideways = false)

    /** Idles the main looper until [condition] holds, for up to 10 s. */
    private fun await(
        what: String,
        condition: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + 10_000
        do {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(10)
        } while (System.currentTimeMillis() < deadline)
        fail(what)
    }

    /** What [read] gives on [look]'s engine thread, once the work posted before it is done. */
    private fun <T> onEngine(
        look: WalLook,
        read: (SkinRuntime?) -> T,
    ): T {
        val got = CompletableFuture<T>()
        look.engine!!.postAlways { got.complete(read(it)) }
        return got.get(5, TimeUnit.SECONDS)
    }

    private fun engineThread(look: WalLook): Thread? = Thread.getAllStackTraces().keys.firstOrNull { it.name == "wal-${look.id}" }

    /** A player whose first status read takes [ms], as a load that runs long. */
    private fun slowPlayer(ms: Long): Player =
        object : Player by FakePlayer() {
            private var waited = false
            override val status: Player.Status
                get() {
                    if (!waited) {
                        waited = true
                        Thread.sleep(ms)
                    }
                    return Player.Status.STOPPED
                }
        }

    @Test(timeout = 20_000)
    fun `no engine thread exists before the first fit`() {
        val look = look()

        assertNull(engineThread(look))
        look.fit(phone)

        await("the engine thread starts") { engineThread(look) != null }
    }

    @Test(timeout = 20_000)
    fun `a fit starts the skin, and its first frame has a layout for that fit`() {
        val look = look()

        look.fit(phone)

        await("the first frame arrives") { look.layout != null }
        assertEquals(phone, look.layout!!.fit)
        assertTrue(look.frames.intValue > 0)
        assertEquals("Test", look.loadedName)
    }

    @Test(timeout = 20_000)
    fun `an import's start returns the name the skin loaded under`() {
        assertEquals("Bento", look(name = "Bento").startAndAwait())
    }

    @Test(timeout = 20_000)
    fun `an import with no name takes the name the skin's skininfo gives`() {
        val named =
            """<WinampAbstractionLayer version="0.8"><skininfo><name>Bento Pro</name></skininfo>""" +
                """<include file="player.xml"/></WinampAbstractionLayer>"""

        assertEquals("Bento Pro", look(wal(named), name = "").startAndAwait())
    }

    @Test(timeout = 20_000)
    fun `an import whose zip cannot be opened throws what opening it threw`() {
        val whole = wal()
        // without its directory, at the end, the file does not open as a zip
        whole.writeBytes(whole.readBytes().let { it.copyOf(it.size - 30) })

        assertThrows(IOException::class.java) { look(whole).startAndAwait() }
    }

    @Test(timeout = 20_000)
    fun `an import that takes longer than its time throws a timeout`() {
        assertThrows(TimeoutException::class.java) { look(player = slowPlayer(1_500), budgetMs = 100).startAndAwait() }
    }

    @Test(timeout = 20_000)
    fun `a blank or throwing skin fails an import without crashing, and does not tell onFailed`() {
        val told = AtomicInteger()
        val blank = look(wal(playerXml = "")).apply { onFailed = { told.incrementAndGet() } }
        val throwing =
            look(
                player =
                    object : Player by FakePlayer() {
                        override val status: Player.Status get() = error("the player is gone")
                    },
            ).apply { onFailed = { told.incrementAndGet() } }

        assertEquals("no main layout", assertThrows(IllegalStateException::class.java) { blank.startAndAwait() }.message)
        assertEquals("the player is gone", assertThrows(IllegalStateException::class.java) { throwing.startAndAwait() }.message)
        repeat(20) { shadowOf(Looper.getMainLooper()).idle() }
        assertEquals(0, told.get())
        assertTrue("its engine thread runs on until it is let go", engineThread(blank)!!.isAlive)
    }

    @Test(timeout = 20_000)
    fun `a blank skin started by a fit tells onFailed it did not start`() {
        val told = ArrayList<Boolean>()
        val look = look(wal(playerXml = "")).apply { onFailed = { told += it } }

        look.fit(phone)

        await("onFailed is told") { told.isNotEmpty() }
        assertEquals(listOf(false), told)
        assertNull(look.layout)
    }

    @Test(timeout = 20_000)
    fun `a start that runs past its time after the first fit falls back`() {
        val told = ArrayList<Boolean>()
        val look = look(player = slowPlayer(300), budgetMs = 100).apply { onFailed = { told += it } }

        look.fit(phone)

        // the main thread notices the time is up, or the engine thread once the load is done
        ShadowLooper.idleMainLooper(150, TimeUnit.MILLISECONDS)
        await("onFailed is told") { told.isNotEmpty() }
        assertEquals("a start failure, not a skin that stopped working", listOf(false), told)
        assertNull("the late skin is not shown", look.layout)
    }

    @Test(timeout = 20_000)
    fun `the hold switch keeps drawing the frame drawn last until it is switched off`() {
        val look = look()
        look.fit(phone)
        await("the first frame arrives") { look.layout != null }
        val graphics = GraphicsContext(FrameLayout(app))
        val layers = WalLayers(graphics::createGraphicsLayer, graphics::releaseGraphicsLayer, clearBelow = true, nodes = false)

        fun draw() =
            CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(ImageBitmap(700, 400)), Size(700f, 400f)) {
                look.draw(this, layers)
            }
        try {
            draw()
            val first = look.engine!!.frame
            assertSame(first, look.shown)
            WalRender.hold = true
            look.fit(WalFit(1000, 2000, 3f, sideways = false))
            await("a frame for the new fit arrives") { look.engine!!.frame !== first }
            draw()
            assertSame("held", first, look.shown)
            WalRender.hold = false
            draw()
            assertSame("the latest", look.engine!!.frame, look.shown)
        } finally {
            WalRender.hold = false
            layers.release()
        }
    }

    @Test(timeout = 20_000)
    fun `three draws that fail tell onFailed the skin stopped working`() {
        val told = ArrayList<Boolean>()
        val look = look().apply { onFailed = { told += it } }
        look.fit(phone)
        await("the first frame arrives") { look.layout != null }

        repeat(WalEngine.MAX_FAILURES) { look.drawFailed(IllegalStateException("draw")) }

        await("onFailed is told") { told.isNotEmpty() }
        assertEquals(listOf(true), told)
    }

    @Test(timeout = 20_000)
    fun `release ends the engine thread`() {
        val look = look()
        look.fit(phone)
        await("the first frame arrives") { look.layout != null }
        val thread = engineThread(look)!!

        look.release(save = false)

        thread.join(5_000)
        assertFalse(thread.isAlive)
        assertNull("nothing is drawn after", look.layout)
    }

    @Test(timeout = 20_000)
    fun `a released look closes the archives of the skins it read from`() {
        val png = ByteArrayOutputStream()
        Bitmap.createBitmap(4, 2, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, png)
        // a sibling the skin takes its player from, with two pictures
        val big =
            dir.newFile().apply {
                ZipOutputStream(outputStream()).use { zip ->
                    for ((path, bytes) in listOf(
                        "player.xml" to player.toByteArray(),
                        "one.png" to png.toByteArray(),
                        "two.png" to png.toByteArray(),
                    )) {
                        zip.putNextEntry(ZipEntry(path))
                        zip.write(bytes)
                        zip.closeEntry()
                    }
                }
            }
        val include = """<include file="../Big/player.xml"/>"""
        val look =
            look(
                wal(skinXml = """<WinampAbstractionLayer version="0.8">$include</WinampAbstractionLayer>"""),
                siblings =
                    mapOf("Big" to big),
            )
        look.fit(phone)
        await("the first frame arrives") { look.layout != null }
        val host = look.host!!
        val thread = engineThread(look)!!
        assertEquals(4 to 2, host.imageSize("../Big/one.png"))

        look.release(save = false)

        thread.join(5_000)
        assertNull("the sibling reads as missing once closed", host.imageSize("../Big/two.png"))
    }

    @Test(timeout = 20_000)
    fun `a release with a save writes the skin's settings, and one without writes nothing`() {
        val saved = look()
        val dropped = look()
        for (l in listOf(saved, dropped)) l.fit(phone)
        await("both skins are on") { saved.layout != null && dropped.layout != null }
        val threads = listOf(engineThread(saved)!!, engineThread(dropped)!!)

        saved.release(save = true)
        dropped.release(save = false)

        threads.forEach { it.join(5_000) }
        assertTrue(prefs.contains(saved.id))
        assertFalse(prefs.contains(dropped.id))
    }

    /** Holds [look]'s engine thread in a task until the returned latch opens. */
    private fun holdEngine(look: WalLook): CountDownLatch {
        val held = CountDownLatch(1)
        val open = CountDownLatch(1)
        look.engine!!.postAlways {
            held.countDown()
            open.await(10, TimeUnit.SECONDS)
        }
        assertTrue(held.await(5, TimeUnit.SECONDS))
        return open
    }

    @Test(timeout = 20_000)
    fun `a save already due when the skin is let go without one lands nowhere, so its settings can be forgotten`() {
        val look = look()
        look.fit(phone)
        await("the first frame arrives") { look.layout != null }
        onEngine(look) { it!!.selectGammaSet("Two") }
        // the task after it, so the save it scheduled is in the queue
        onEngine(look) { }
        val open = holdEngine(look)
        // past the time the save waited for: it is due, behind the task holding the engine thread
        ShadowLooper.idleMainLooper(WalLook.SAVE_DELAY_MS * 2, TimeUnit.MILLISECONDS)
        val thread = engineThread(look)!!

        look.release(save = false)
        WalSettings(app).forget(look.id)
        open.countDown()

        thread.join(5_000)
        assertFalse(thread.isAlive)
        assertFalse(prefs.contains(look.id))
    }

    @Test(timeout = 20_000)
    fun `a save asked for just before the skin is let go without one lands nowhere`() {
        val look = look()
        look.fit(phone)
        await("the first frame arrives") { look.layout != null }
        onEngine(look) { it!!.selectGammaSet("Two") }
        val open = holdEngine(look)
        // as a player surface saves when it goes, or a source's skin parks the look
        look.park()
        val thread = engineThread(look)!!

        look.release(save = false)
        WalSettings(app).forget(look.id)
        open.countDown()

        thread.join(5_000)
        assertFalse(thread.isAlive)
        assertFalse(prefs.contains(look.id))
    }

    @Test(timeout = 20_000)
    fun `a menu the skin opens while an import starts it is answered at once, and later ones wait for the player`() {
        val menu = SkinMenu(listOf(SkinMenu.Command(1, "Item", checked = false, disabled = false)))
        val asLoaded = CompletableFuture<Int?>()
        lateinit var look: WalLook
        // the runtime reads the player's status as it loads: the skin opens its menu then
        val opensMenu =
            object : Player by FakePlayer() {
                override val status: Player.Status
                    get() {
                        if (!asLoaded.isDone) asLoaded.complete(look.host!!.chooseFromMenu(menu))
                        return Player.Status.STOPPED
                    }
            }
        look = look(player = opensMenu)

        assertEquals("Test", look.startAndAwait())
        assertEquals("answered with nothing picked", 0, asLoaded.get(1, TimeUnit.SECONDS))

        val later = CompletableFuture<Int?>()
        look.engine!!.postAlways { later.complete(look.host!!.chooseFromMenu(menu)) }
        await("the menu waits for the player") { look.menu === menu }
        assertFalse(later.isDone)
        look.answerMenu(menu, 1)
        assertEquals(1, later.get(5, TimeUnit.SECONDS))
    }

    @Test(timeout = 20_000)
    fun `a theme change is saved once, after the changes rest`() {
        val writes = AtomicInteger()
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> writes.incrementAndGet() }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        val look = look()
        look.fit(phone)
        await("the first frame arrives") { look.layout != null }

        onEngine(look) { it!!.selectGammaSet("Two") }
        onEngine(look) { it!!.selectGammaSet("One") }
        onEngine(look) { it!!.selectGammaSet("Two") }
        repeat(20) { shadowOf(Looper.getMainLooper()).idle() }
        assertEquals("nothing is saved while changes come", 0, writes.get())

        ShadowLooper.idleMainLooper(WalLook.SAVE_DELAY_MS + 100, TimeUnit.MILLISECONDS)
        await("the theme is saved") { writes.get() > 0 }
        repeat(20) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        assertEquals("saved once", 1, writes.get())
        assertTrue(prefs.getString(look.id, null)!!.contains("\"theme\":\"Two\""))
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
    }

    @Test(timeout = 20_000)
    fun `the theme put back as the skin loads is no change to save`() {
        val first = look()
        first.fit(phone)
        await("the first frame arrives") { first.layout != null }
        onEngine(first) { it!!.selectGammaSet("Two") }
        first.release(save = true)
        await("the theme is kept") { prefs.getString(first.id, null)?.contains("Two") == true }
        val writes = AtomicInteger()
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> writes.incrementAndGet() }
        prefs.registerOnSharedPreferenceChangeListener(listener)

        val again =
            WalLook(first.id, wal(), "Test", emptyMap(), app.cacheDir, WalSettings(app), FakePlayer())
                .also { looks += it }
        again.fit(phone)
        await("the skin is on again") { again.layout != null }
        ShadowLooper.idleMainLooper(WalLook.SAVE_DELAY_MS * 2, TimeUnit.MILLISECONDS)
        repeat(20) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }

        assertEquals("Two", onEngine(again) { it!!.currentGammaSet })
        assertEquals(0, writes.get())
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
    }

    @Test(timeout = 20_000)
    fun `a host action with no sink is dropped, and with one it arrives on main`() {
        val look = look()
        look.fit(phone)
        await("the first frame arrives") { look.layout != null }
        val heard = ArrayList<String>()
        val sink =
            object : WalSink {
                override fun hostAction(
                    action: String,
                    param: String?,
                ) {
                    heard += "$action on ${if (Looper.myLooper() == Looper.getMainLooper()) "main" else "another thread"}"
                }

                override fun openFile(andPlay: Boolean) = Unit
            }

        look.pointerDown(110, 20)
        look.pointerUp(110, 20)
        onEngine(look) { }
        repeat(5) { shadowOf(Looper.getMainLooper()).idle() }
        look.sink = sink
        assertTrue("dropped with no sink", heard.isEmpty())

        look.pointerDown(110, 20)
        look.pointerUp(110, 20)
        onEngine(look) { }
        await("the action arrives") { heard.isNotEmpty() }
        assertEquals(listOf("MINIMIZE on main"), heard)
    }

    @Test(timeout = 20_000)
    fun `fifty quick moves cost the main thread one post or a few`() {
        val look = look()
        look.fit(phone)
        await("the first frame arrives") { look.layout != null }
        repeat(5) { shadowOf(Looper.getMainLooper()).idle() }
        val before = posts.get()

        look.pointerDown(15, 15)
        repeat(50) { look.pointerMove(15 + it, 15) }
        onEngine(look) { }

        val made = posts.get() - before
        assertTrue("$made main posts", made in 1..3)
    }

    @Test(timeout = 20_000)
    fun `seqs increase across downs and double taps, also before the skin starts`() {
        val look = look()
        val early = look.pointerDown(1, 1)
        look.fit(phone)
        await("the first frame arrives") { look.layout != null }

        val seqs = listOf(early, look.doubleTap(1, 1), look.pointerDown(1, 1), look.doubleTap(1, 1))

        assertEquals(seqs.sorted().distinct(), seqs)
    }

    @Test(timeout = 20_000)
    fun `a look a player surface holds keeps its drawing caches, an idle one lets them go`() {
        val look = look()
        look.fit(phone)
        await("the first frame arrives") { look.layout != null }

        assertTrue(look.turn.tryLock())
        look.trimDrawing()
        assertEquals("the surface holding its turn keeps them", 0, look.trims)
        look.turn.unlock()
        look.trimDrawing()
        assertEquals(1, look.trims)
    }

    @Test(timeout = 20_000)
    fun `a released look starts nothing on a later fit`() {
        val look = look()
        look.release(save = true)

        look.fit(phone)

        assertTrue(look.released)
        assertNull(look.engine)
        assertNull(engineThread(look))
    }

    @Test(timeout = 20_000)
    fun `a press on a move area or a button comes back in the layout as the move target, and a press on a slider does not`() {
        val bar =
            """<layer id="bar" x="0" y="40" w="200" h="14" move="1"/>""" +
                """<slider id="vol" action="volume" x="0" y="70" w="200" h="10" orientation="h"/>"""
        val look = look(file = wal(playerXml = player.replace("</layout>", "$bar</layout>")))
        look.fit(phone)
        await("the first frame arrives") { look.layout != null }

        val onBar = look.pointerDown(150, 45)
        look.pointerUp(150, 45)
        await("the press on the bar is named") { look.layout?.moveTarget == onBar }

        val onPlay = look.pointerDown(15, 15)
        look.pointerUp(15, 15)
        await("the press on Play is named") { look.layout?.moveTarget == onPlay }

        look.pointerDown(100, 75)
        await("the press on the slider is not") { look.layout?.moveTarget == null }
    }

    @Test
    fun `where the stack was moved is kept for the next look of the skin, upright and sideways apart`() {
        val first = look()
        first.places[false] = IntOffset(-150, 300)

        val again =
            WalLook(
                first.id,
                wal(),
                "Test",
                emptyMap(),
                app.cacheDir,
                WalSettings(app),
                FakePlayer(),
            ).also { looks += it }

        assertEquals(IntOffset(-150, 300), again.places[false])
        assertNull(again.places[true])
    }

    @Test
    fun `a stack put back where the engine puts it keeps no place, upright or sideways`() {
        val look = look()
        look.places[false] = IntOffset(-150, 300)
        look.places[true] = IntOffset(30, 0)

        look.places.clear()

        assertNull(look.places[false])
        assertNull(look.places[true])
        assertNull(WalSettings(app).place(look.id, sideways = false))
        assertNull(WalSettings(app).place(look.id, sideways = true))
    }

    /**
     * On [look]'s engine, in one touch: the skin's [menu], and when an item is picked from it, the
     * main window moved into the top left corner, as CornerAmp moves to the corner picked.
     */
    private fun cornerFromMenu(
        look: WalLook,
        menu: SkinMenu,
    ) = look.engine!!.post { rt ->
        rt.gesture(fresh = true) {
            if (rt.menuChoice(menu) != 0) {
                val (w, h) = checkNotNull(rt.mainLayout).size
                checkNotNull(rt.mainLayout).makiCall("resize", listOf(Value.Int(0), Value.Int(0), Value.Int(w), Value.Int(h)))
            }
        }
    }

    @Test(timeout = 20_000)
    fun `a corner picked from the skin's menu puts the stack back where the engine puts it, and a dismissed menu does not`() {
        val look = look()
        look.fit(phone)
        await("the first frame arrives") { look.layout != null }
        look.places[false] = IntOffset(-150, 300)
        look.places[true] = IntOffset(30, 0)
        val menu = SkinMenu(listOf(SkinMenu.Command(1, "Left Up Corner", checked = false, disabled = false)))

        cornerFromMenu(look, menu)
        await("the menu waits for the player") { look.menu === menu }
        look.answerMenu(menu, 0)
        assertEquals(0, onEngine(look) { it?.edgePlacements })
        await("the menu closed") { look.menu == null }
        assertEquals("dismissed", IntOffset(-150, 300), look.places[false])

        cornerFromMenu(look, menu)
        await("the menu waits again") { look.menu === menu }
        look.answerMenu(menu, 1)
        await("the place is dropped") { look.places[false] == null }
        assertNull(look.places[true])
        assertNull(WalSettings(app).place(look.id, sideways = false))
        assertEquals(1, look.layout?.edgePlacements)

        // a place the listener drags to after it stays while the skin places itself no more
        look.places[false] = IntOffset(10, 20)
        look.requestTick()
        assertEquals(1, onEngine(look) { it?.edgePlacements })
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(IntOffset(10, 20), look.places[false])
    }

    @Test
    fun `a look let go without a save keeps no place, so a removed skin's settings stay forgotten`() {
        val look = look()
        look.release(save = false)
        WalSettings(app).forget(look.id)

        look.places[false] = IntOffset(-150, 300)

        assertNull(WalSettings(app).place(look.id, sideways = false))
    }

    @Test(timeout = 20_000)
    fun `a look tells its engine when the screen rests and when it wakes`() {
        val look = look()
        look.fit(phone)
        val engine = look.engine!!
        assertFalse("resting until a surface says otherwise", engine.awake)

        look.awake = true
        assertTrue(engine.awake)
        look.awake = false
        assertFalse(engine.awake)
    }

    @Test(timeout = 20_000)
    fun `awake set before the first fit reaches the engine`() {
        val look = look()

        look.awake = true
        look.fit(phone)

        assertTrue(look.engine!!.awake)
        await("the first frame arrives") { look.layout != null }
        assertTrue(look.engine!!.awake)
    }

    @Test(timeout = 20_000)
    fun `each call the listener makes on the skin sets the time of the last input, and nothing else does`() {
        val look = look()
        val menu = skinMenuOf("One")
        val calls =
            listOf<Pair<String, () -> Unit>>(
                "down" to { look.pointerDown(1, 1) },
                "move" to { look.pointerMove(2, 2) },
                "up" to { look.pointerUp(2, 2) },
                "cancel" to { look.pointerCancel() },
                "double tap" to { look.doubleTap(1, 1) },
                "right click" to { look.rightClick(1) },
                "menu answer" to { look.answerMenu(menu, 1) },
                "Back" to { look.closeTopWindow() },
                "theme pick" to { look.pickTheme("Two") },
            )
        look.fit(phone)
        await("the first frame arrives") { look.layout != null }

        for ((what, call) in calls) {
            ShadowSystemClock.advanceBy(Duration.ofMillis(100))
            call()
            assertEquals(what, SystemClock.uptimeMillis(), look.lastInputAt)
        }
        val last = look.lastInputAt
        ShadowSystemClock.advanceBy(Duration.ofMillis(100))
        look.requestTick()
        look.dismissMenu()
        look.save()
        look.fit(phone.copy(widthPx = 1000))
        look.awake = true
        assertEquals("ticks, fits, saves and the screen go by unseen", last, look.lastInputAt)
    }

    private companion object {
        val runs = AtomicInteger()
    }
}
