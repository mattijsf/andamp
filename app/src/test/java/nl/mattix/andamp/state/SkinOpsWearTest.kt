// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Context
import android.os.Looper
import androidx.compose.ui.graphics.Color
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import nl.mattix.andamp.modernskin.maki.Value
import nl.mattix.andamp.modernskin.skin.FakePlayer
import nl.mattix.andamp.modernskin.skin.PopupMenu
import nl.mattix.andamp.skin.BundledSkins
import nl.mattix.andamp.skin.FakePalette
import nl.mattix.andamp.skin.LiveSkins
import nl.mattix.andamp.skin.SkinDist
import nl.mattix.andamp.wal.WalEngine
import nl.mattix.andamp.wal.WalFit
import nl.mattix.andamp.wal.WalLook
import nl.mattix.andamp.wal.WalSettings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A music source's skin, worn over the listener's own. Wearing one leaves the listener's
 * choice as it was: a track from elsewhere, or a worn skin that is removed or will not
 * open, puts the listener's own back on. A skin the listener picks wins over one a source
 * asked for earlier. A modern skin's look is parked under a source's skin, let go when it is no
 * longer needed, and gives way to the classic skin under it when its engine fails.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class SkinOpsWearTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val state = WinampState()
    private lateinit var library: SkinLibrary
    private lateinit var ops: SkinOps

    /** A skin of the listener's own, so a pick has a row to pick that is not a bundled one. */
    private lateinit var mine: String

    @Before
    fun setUp() {
        app
            .getSharedPreferences("skins", 0)
            .edit()
            .clear()
            .commit()
        app
            .getSharedPreferences("wal_skins", 0)
            .edit()
            .clear()
            .commit()
        java.io.File(app.filesDir, "skins").deleteRecursively()
        library = SkinLibrary(app)
        // the listener's own choice, made before this launch
        library.currentId = BundledSkins.LIGHT.id
        mine = library.save(app.assets.open(BundledSkins.SPOT.asset).use { it.readBytes() }, "Mine.wsz").id
        ops = opsOn(state)
        ops.start()
        // the rows too, listed a hop after the skin itself goes on
        await { ops.skin != null && ops.currentId == BundledSkins.LIGHT.id && state.skinEntries.isNotEmpty() }
    }

    /** Whether modern skins are on now, as the switch has them; [SkinOps.betaChanged] is told of a change. */
    private var gate = true

    /** Every modern look opened, in order. */
    private val opened = CopyOnWriteArrayList<WalLook>()

    /** Runs as a look is made, on IO under the lock: a test can hold the load there. */
    private var beforeOpen: () -> Unit = {}

    @After
    fun releaseLooks() = opened.forEach { it.release(save = false) }

    private fun opsOn(
        state: WinampState,
        modernOn: Boolean = true,
        live: LiveSkins = CurrentSkin.live,
        main: CoroutineDispatcher = Dispatchers.Main,
    ) = SkinOps(
        app,
        state,
        CoroutineScope(SupervisorJob() + main),
        library,
        live = live,
        modernOn = { modernOn && gate },
        openModern = { id, file, name, siblings ->
            beforeOpen()
            WalLook(id, file, name, siblings, app.cacheDir, WalSettings(app), FakePlayer()).also { opened += it }
        },
    )

    private val phone = WalFit(1080, 2000, 3f, sideways = false)

    /** Puts [look] on as a player surface does, and waits for its first frame. */
    private fun fitted(look: WalLook): WalLook {
        look.fit(phone)
        await { look.layout != null }
        return look
    }

    /**
     * Idles the looper before each look at the condition, so a condition the setup already
     * satisfies is not accepted before the work under test has run.
     */
    private fun await(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        do {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(10)
        } while (System.currentTimeMillis() < deadline)
        fail("the condition holds within 10 s")
    }

    /** Everything queued has run, for an assertion about what did not happen. */
    private fun settle() {
        repeat(SETTLE_ROUNDS) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
    }

    @Test
    fun `a source's skin goes on without changing what the listener chose`() {
        ops.wear(BundledSkins.SPOT.id)
        await { ops.currentId == BundledSkins.SPOT.id }

        assertEquals("the listener's choice is untouched", BundledSkins.LIGHT.id, library.currentId)
        assertEquals("the worn skin is recorded for the home screen", BundledSkins.SPOT.id, library.worn)
    }

    @Test
    fun `a track from elsewhere puts the listener's own back on`() {
        ops.wear(BundledSkins.SPOT.id)
        await { ops.currentId == BundledSkins.SPOT.id }

        ops.wear(null)
        await { ops.currentId == BundledSkins.LIGHT.id }

        assertNull(library.wearing)
    }

    @Test
    fun `a skin that is not installed is not worn - the listener's own goes back on`() {
        // worn first, so what is asserted afterwards is a move only the second
        // wear can have made rather than the state the setup left behind
        ops.wear(BundledSkins.SPOT.id)
        await { ops.currentId == BundledSkins.SPOT.id }

        ops.wear("no-such-skin")
        await { ops.currentId == BundledSkins.LIGHT.id }

        assertNull(library.wearing)
        assertEquals(BundledSkins.LIGHT.id, library.worn)
    }

    @Test
    fun `a source's skin that will not open takes the listener's own back, not the base`() {
        val rotten = library.save("this is not a zip".toByteArray(), "Rotten.wsz").id
        ops.wear(mine)
        await { ops.currentId == mine }

        ops.wear(rotten)
        await { ops.currentId == BundledSkins.LIGHT.id }

        assertNull("a skin that will not open is not recorded as worn", library.wearing)
        assertEquals(BundledSkins.LIGHT.id, library.worn)
    }

    @Test
    fun `removing the worn skin goes back to the listener's own, not to the base`() {
        ops.wear(mine)
        await { ops.currentId == mine }

        ops.remove(mine)
        await { ops.currentId == BundledSkins.LIGHT.id }

        assertNull(library.wearing)
        assertEquals(BundledSkins.LIGHT.id, library.currentId)
    }

    @Test
    fun `picking the skin a source is wearing makes it the listener's own`() {
        // the menu ticks what is on screen, so this is the listener tapping the
        // skin they can already see to keep it
        ops.wear(BundledSkins.SPOT.id)
        await { ops.currentId == BundledSkins.SPOT.id }

        ops.apply(BundledSkins.SPOT.id)
        await { library.currentId == BundledSkins.SPOT.id }

        // and the next track from the phone's own files leaves the pick alone
        ops.wear(null)
        settle()
        assertEquals(BundledSkins.SPOT.id, ops.currentId)
        assertNull(library.wearing)
        assertEquals(BundledSkins.SPOT.id, library.worn)
    }

    @Test
    fun `a skin the listener picks beats a source's skin that was already on its way`() {
        ops.wear(BundledSkins.SPOT.id)
        ops.apply(mine)

        await { ops.currentId == mine }
        settle()

        assertNull("the source's skin is not worn over the pick", library.wearing)
        assertEquals(mine, library.currentId)
        assertEquals("the home screen records the picked skin", mine, library.worn)
    }

    @Test
    fun `a pick by id wears that skin whatever the order of the rows`() {
        state.skinEntries = state.skinEntries.reversed()

        ops.apply(mine)

        await { ops.currentId == mine }
        assertEquals("Mine.wsz", ops.skin!!.name)
    }

    // modern skins: their engine draws them, and the classic skin on screen is the last
    // classic choice

    @Test
    fun `a modern skin chosen shows the last classic choice and pins a following widget to it`() {
        val modern = library.addModern(app, "Bento")

        ops.apply(modern)

        await { ops.currentId == modern }
        assertEquals(BundledSkins.LIGHT.name, ops.skin!!.name)
        assertEquals(modern, library.currentId)
        assertEquals(BundledSkins.LIGHT.id, ops.widgetSkin)
        assertTrue(library.widgetSkinAutomatic)
    }

    @Test
    fun `picking AndAmp Dark while a modern skin is worn shows the base and lets go of the pin`() {
        val modern = library.addModern(app, "Bento")
        ops.apply(modern)
        await { ops.currentId == modern }

        ops.reset()

        await { library.currentId == SkinEntry.BASE_ID && ops.widgetSkin == null }
        assertEquals(SkinEntry.BASE_ID, ops.currentId)
        assertSame(ops.baseSkin, ops.skin)
    }

    @Test
    fun `a classic skin chosen over a modern one lets go of the pin`() {
        val modern = library.addModern(app, "Bento")
        ops.apply(modern)
        await { ops.currentId == modern }

        ops.apply(mine)

        await { ops.currentId == mine }
        assertNull(ops.widgetSkin)
        assertEquals(mine, library.lastClassic)
    }

    @Test
    fun `removing the worn modern skin puts the last classic choice on`() {
        val modern = library.addModern(app, "Bento")
        ops.apply(modern)
        await { ops.currentId == modern }

        ops.remove(modern)

        await { ops.currentId == BundledSkins.LIGHT.id }
        assertEquals(BundledSkins.LIGHT.name, ops.skin!!.name)
        assertEquals(BundledSkins.LIGHT.id, library.currentId)
        assertNull("the automatic pin goes with it", ops.widgetSkin)
    }

    @Test
    fun `removing the last classic choice under a modern skin changes the classic skin on screen`() {
        ops.apply(mine)
        await { ops.currentId == mine }
        val modern = library.addModern(app, "Bento")
        ops.apply(modern)
        await { ops.currentId == modern }
        assertEquals("Mine.wsz", ops.skin!!.name)

        ops.remove(mine)

        await { ops.skin === ops.baseSkin }
        assertEquals("the modern choice stays", modern, ops.currentId)
    }

    @Test
    fun `a source's skin goes on over a modern choice and the widget keeps its pin`() {
        val modern = library.addModern(app, "Bento")
        ops.apply(modern)
        await { ops.currentId == modern }

        ops.wear(BundledSkins.SPOT.id)
        await { ops.currentId == BundledSkins.SPOT.id }
        assertEquals(BundledSkins.LIGHT.id, ops.widgetSkin)
        assertTrue(library.widgetSkinAutomatic)

        ops.wear(null)
        await { ops.currentId == modern }
        assertEquals(BundledSkins.LIGHT.name, ops.skin!!.name)
    }

    @Test
    fun `a widget skin picked by hand is kept and not marked automatic`() {
        ops.chooseWidgetSkin(BundledSkins.SPOT.id)

        await { ops.widgetSkin == BundledSkins.SPOT.id }
        assertFalse(library.widgetSkinAutomatic)

        ops.chooseWidgetSkin(null)
        await { ops.widgetSkin == null }
    }

    @Test
    fun `a widget skin picked by hand stays through a modern skin and a classic one after it`() {
        ops.chooseWidgetSkin(mine)
        await { ops.widgetSkin == mine }
        val modern = library.addModern(app, "Bento")

        ops.apply(modern)
        await { ops.currentId == modern }
        assertEquals("not pinned over", mine, ops.widgetSkin)

        ops.apply(BundledSkins.SPOT.id)
        await { ops.currentId == BundledSkins.SPOT.id }
        assertEquals("not let go", mine, ops.widgetSkin)
        assertFalse(library.widgetSkinAutomatic)
    }

    @Test
    fun `a widget skin picked by hand stays when the worn modern skin is removed`() {
        ops.chooseWidgetSkin(mine)
        await { ops.widgetSkin == mine }
        val modern = library.addModern(app, "Bento")
        ops.apply(modern)
        await { ops.currentId == modern }

        ops.remove(modern)

        await { ops.currentId == BundledSkins.LIGHT.id }
        assertEquals(mine, ops.widgetSkin)
    }

    @Test
    fun `a widget skin picked by hand stays when modern skins are off at start`() {
        val modern = library.addModern(app, "Bento")
        library.choose(modern)
        library.chooseWidgetSkin(mine)

        val restarted = opsOn(WinampState(), modernOn = false)
        restarted.start()

        await { restarted.currentId == BundledSkins.LIGHT.id && restarted.skin != null }
        assertEquals(mine, restarted.widgetSkin)
    }

    @Test
    fun `a widget set to a classic skin keeps it while a source's skin is on the player`() {
        ops.chooseWidgetSkin(mine)
        await { ops.widgetSkin == mine }

        ops.wear(BundledSkins.SPOT.id)
        await { ops.currentId == BundledSkins.SPOT.id }

        assertEquals(mine, ops.widgetSkin)
        assertEquals("Mine.wsz", CurrentSkin.widgetKeyed(app, library, LiveSkins(FakePalette(null)))!!.second.name)
    }

    @Test
    fun `modern rows are listed only while modern skins are on`() {
        val modern = library.addModern(app, "Bento")

        val on = WinampState()
        opsOn(on).start()
        val off = WinampState()
        opsOn(off, modernOn = false).start()

        await { on.skinEntries.isNotEmpty() && off.skinEntries.isNotEmpty() }
        assertTrue(on.skinEntries.any { it.id == modern })
        assertTrue(off.skinEntries.none { it.id == modern })
        assertTrue("classic rows are listed either way", off.skinEntries.any { it.id == mine })
    }

    @Test
    fun `with modern skins off a modern choice gives way to the last classic choice at start`() {
        val modern = library.addModern(app, "Bento")
        library.choose(BundledSkins.LIGHT.id)
        library.choose(modern)

        val restarted = opsOn(WinampState(), modernOn = false)
        restarted.start()

        await { restarted.currentId == BundledSkins.LIGHT.id && restarted.skin != null }
        assertEquals(BundledSkins.LIGHT.id, library.currentId)
        assertNull("the automatic pin goes", restarted.widgetSkin)
    }

    @Test
    fun `with modern skins on a modern choice stays on at start with the last classic choice on screen`() {
        val modern = library.addModern(app, "Bento")
        library.choose(BundledSkins.LIGHT.id)
        library.choose(modern)

        val restarted = opsOn(WinampState())
        restarted.start()

        await { restarted.currentId == modern && restarted.skin != null }
        assertEquals(BundledSkins.LIGHT.name, restarted.skin!!.name)
        assertEquals(BundledSkins.LIGHT.id, restarted.widgetSkin)
    }

    @Test
    fun `turning modern skins off puts the last classic choice back on and takes the modern rows out`() {
        val modern = library.addModern(app, "Bento")
        ops.apply(mine)
        await { ops.currentId == mine }
        ops.apply(modern)
        ops.betaChanged(true)
        await { ops.currentId == modern && state.skinEntries.any { it.id == modern } }

        gate = false
        ops.betaChanged(false)

        await { ops.currentId == mine && state.skinEntries.none { it.id == modern } }
        assertEquals("Mine.wsz", ops.skin!!.name)
        assertEquals(mine, library.currentId)
        assertNull("the automatic pin goes", ops.widgetSkin)
        assertTrue("the file stays", library.has(modern))
    }

    @Test
    fun `turning modern skins off leaves a source's skin on screen and takes the modern choice off`() {
        val modern = library.addModern(app, "Bento")
        ops.apply(modern)
        await { ops.currentId == modern }
        ops.wear(BundledSkins.SPOT.id)
        await { ops.currentId == BundledSkins.SPOT.id }
        val drawn = ops.skin

        gate = false
        ops.betaChanged(false)

        await { library.currentId == BundledSkins.LIGHT.id && state.skinEntries.none { it.id == modern } }
        settle()
        assertEquals(BundledSkins.SPOT.id, ops.currentId)
        assertSame("the source's skin is not loaded again", drawn, ops.skin)
        ops.wear(null)
        await { ops.currentId == BundledSkins.LIGHT.id }
    }

    @Test
    fun `turning modern skins on lists their rows again and changes nothing worn`() {
        val modern = library.addModern(app, "Bento")
        gate = false
        ops.betaChanged(false)
        await { state.skinEntries.none { it.id == modern } }

        gate = true
        ops.betaChanged(true)

        await { state.skinEntries.any { it.id == modern } }
        assertEquals(BundledSkins.LIGHT.id, ops.currentId)
        assertEquals(BundledSkins.LIGHT.id, library.currentId)
    }

    // the modern skin's look

    /** Chooses a new modern skin, one that [loads] when asked, and waits for its look to go on. */
    private fun wornModern(loads: Boolean = false): WalLook {
        ops.apply(library.addModern(app, "Bento", loads = loads))
        await { ops.wal != null }
        return ops.wal!!
    }

    @Test
    fun `a modern skin chosen goes on with its look, which waits for a player surface to start`() {
        val modern = library.addModern(app, "Bento")

        ops.apply(modern)

        await { ops.wal != null }
        assertEquals(modern, ops.wal!!.id)
        assertEquals(1, opened.size)
        assertNull("nothing runs before a player surface fits it", ops.wal!!.engine)
    }

    @Test
    fun `a source's skin parks the modern look, and the same look comes back when the source stops`() {
        val look = wornModern()

        ops.wear(BundledSkins.SPOT.id)
        await { ops.currentId == BundledSkins.SPOT.id }
        assertNull("not on screen under the source's skin", ops.wal)
        assertFalse("parked, not let go", look.released)

        ops.wear(null)
        await { ops.currentId == look.id }
        assertSame(look, ops.wal)
        assertEquals("opened once", 1, opened.size)
    }

    @Test
    fun `a palette change keeps the modern look and opens nothing`() {
        val dark = SkinDist.baselineScheme("dark")
        val palette = FakePalette(dark)
        val modern = library.addModern(app, "Bento")
        library.choose(modern)
        val live = opsOn(WinampState(), live = LiveSkins(palette))
        live.start()
        await { live.wal != null }
        val look = live.wal
        val skin = live.skin

        palette.scheme = dark.copy(primary = Color(0xFF00E250))
        live.paletteChanged()

        await { live.skin !== skin }
        assertSame(look, live.wal)
        assertEquals(1, opened.size)
    }

    @Test
    fun `turning modern skins off lets go of the look on screen`() {
        val look = wornModern()

        gate = false
        ops.betaChanged(false)

        await { ops.wal == null && look.released }
    }

    @Test
    fun `turning modern skins off lets go of a parked look`() {
        val look = wornModern()
        ops.wear(BundledSkins.SPOT.id)
        await { ops.currentId == BundledSkins.SPOT.id }

        gate = false
        ops.betaChanged(false)

        await { look.released }
        ops.wear(null)
        await { ops.currentId == BundledSkins.LIGHT.id }
        assertNull(ops.wal)
        assertEquals("nothing new opened", 1, opened.size)
    }

    @Test
    fun `removing the worn modern skin lets it go without writing its settings`() {
        val look = fitted(wornModern(loads = true))
        val modern = look.id
        val thread = Thread.getAllStackTraces().keys.single { it.name == "wal-$modern" }

        ops.remove(modern)

        await { ops.currentId == BundledSkins.LIGHT.id }
        assertTrue(look.released)
        thread.join(5_000)
        settle()
        assertFalse(
            "nothing kept for it",
            app.getSharedPreferences("wal_skins", Context.MODE_PRIVATE).contains(modern),
        )
    }

    @Test
    fun `picking AndAmp Dark lets go of the modern look`() {
        val look = wornModern()

        ops.reset()

        await { ops.wal == null && look.released }
    }

    /** Chooses a modern skin whose look does not start, fits it, and waits for the classic skin under it. */
    private fun failedModern(): String {
        val look = wornModern()
        look.fit(phone)
        await { ops.wal == null && ops.currentId == BundledSkins.LIGHT.id }
        return look.id
    }

    @Test
    fun `a modern skin whose look does not start shows the classic skin ticked and keeps the choice`() {
        val modern = failedModern()

        assertEquals(BundledSkins.LIGHT.name, ops.skin!!.name)
        assertEquals("the choice stays, to be tried at the next start", modern, library.currentId)
        assertTrue(opened.single().released)
        assertNull("a start that fails says nothing", state.prompt)
    }

    @Test
    fun `a modern skin that failed is not opened again by a removal or a source's skin`() {
        failedModern()

        ops.remove(mine)
        settle()
        ops.wear(BundledSkins.SPOT.id)
        await { ops.currentId == BundledSkins.SPOT.id }
        ops.wear(null)
        await { ops.currentId == BundledSkins.LIGHT.id }
        settle()

        assertEquals(1, opened.size)
        assertNull(ops.wal)
    }

    @Test
    fun `a modern skin that failed opens again when the listener picks it again`() {
        val modern = failedModern()

        ops.apply(modern)

        await { ops.currentId == modern && ops.wal != null }
        assertEquals(2, opened.size)
    }

    @Test
    fun `a modern skin that failed opens again at the next start`() {
        val modern = failedModern()

        val restarted = opsOn(WinampState())
        restarted.start()

        await { restarted.currentId == modern && restarted.wal != null }
        assertEquals(2, opened.size)
    }

    @Test
    fun `a modern skin that stops working says so and shows the classic skin`() {
        val look = fitted(wornModern(loads = true))

        repeat(3) { look.drawFailed(IllegalStateException("draw")) }

        await { ops.wal == null && state.prompt != null }
        assertEquals("“Bento” stopped working", state.prompt!!.title)
        assertEquals(BundledSkins.LIGHT.id, ops.currentId)
        assertEquals(look.id, library.currentId)
        assertTrue(look.released)
    }

    @Test
    fun `picking the source's skin on screen lets go of the modern look parked under it`() {
        val look = wornModern()
        ops.wear(BundledSkins.SPOT.id)
        await { ops.currentId == BundledSkins.SPOT.id }
        assertFalse(look.released)

        // the Skins menu ticks the source's skin; tapping it makes it the listener's choice
        ops.apply(BundledSkins.SPOT.id)

        await { library.currentId == BundledSkins.SPOT.id && look.released }
        ops.wear(null)
        settle()
        assertEquals(BundledSkins.SPOT.id, ops.currentId)
        assertNull(ops.wal)
        assertEquals("nothing opened again", 1, opened.size)
    }

    @Test
    fun `a look that fails while a load holding it is on its way shows the classic skin ticked, and a pick opens it again`() {
        val dark = SkinDist.baselineScheme("dark")
        val palette = FakePalette(dark)
        // holds a palette change's load before it picks up the look on screen
        val held = HeldPalette(palette)
        val main = CountingMain()
        val modern = library.addModern(app, "Bento", loads = true)
        library.choose(modern)
        val prompted = WinampState()
        val live = opsOn(prompted, live = LiveSkins(held), main = main)
        live.start()
        await { live.wal != null }
        val look = fitted(live.wal!!)

        held.arm()
        palette.scheme = dark.copy(primary = Color(0xFF00E250))
        live.paletteChanged()
        await { held.holding.count == 0L }
        // the look stops working while the load waits: the news is on its way to main
        repeat(WalEngine.MAX_FAILURES) { look.drawFailed(IllegalStateException("draw")) }
        val broken = java.util.concurrent.CompletableFuture<Unit>()
        look.engine!!.postAlways { broken.complete(Unit) }
        broken.get(5, TimeUnit.SECONDS)
        // the load picks up the look, which has not failed yet as main sees it, and comes back to main
        assertTrue("the load came back", main.awaitDispatch { held.release() })

        // main hears of the failure first, then puts what the load gave
        settle()

        assertEquals("the classic skin under it is ticked", BundledSkins.LIGHT.id, live.currentId)
        assertNull(live.wal)
        assertTrue(look.released)
        assertEquals("“Bento” stopped working", prompted.prompt?.title)
        assertEquals("the choice stays", modern, library.currentId)

        live.apply(modern)

        await { live.currentId == modern && live.wal != null }
        assertEquals(2, opened.size)
    }

    @Test
    fun `a wear dropped by a later choice lets go of the look it opened`() {
        val modern = library.addModern(app, "Bento")
        library.choose(modern)
        library.wear(BundledSkins.SPOT.id, SkinLibrary.choices)
        // the source's skin is worn at the start, so the modern choice has no look yet
        val restarted = opsOn(WinampState())
        restarted.start()
        await { restarted.currentId == BundledSkins.SPOT.id }
        assertTrue(opened.isEmpty())
        val loading = CountDownLatch(1)
        val letGo = CountDownLatch(1)
        beforeOpen = {
            loading.countDown()
            letGo.await(10, TimeUnit.SECONDS)
        }

        restarted.wear(null)
        await { loading.count == 0L }
        // the listener chooses while the source's skin comes off
        SkinLibrary.choosing()
        letGo.countDown()

        await { opened.size == 1 && opened.single().released }
        assertNull(restarted.wal)
        assertEquals(BundledSkins.SPOT.id, restarted.currentId)
    }

    @Test
    fun `a trim lets go of a parked look's drawing and leaves the menu of the look on screen open`() {
        val look = fitted(wornModern(loads = true))
        ops.wear(BundledSkins.SPOT.id)
        await { ops.currentId == BundledSkins.SPOT.id }

        app.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
        assertEquals("the parked look let go", 1, look.trims)

        ops.wear(null)
        await { ops.wal === look }
        assertTrue("a player surface holds it", look.turn.tryLock())
        val heard = java.util.concurrent.CompletableFuture<Int>()
        look.engine!!.post { rt ->
            val menu = PopupMenu(rt).apply { makiCall("addcommand", listOf(Value.Str("Item"), Value.Int(1))) }
            heard.complete(rt.gesture(fresh = true) { menu.makiCall("popatmouse", emptyList())!!.asInt })
        }
        await { look.menu != null }

        app.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
        settle()

        assertEquals("the look on screen keeps its drawing", 1, look.trims)
        assertTrue("its menu stays open", look.menu != null && !heard.isDone)
        look.answerMenu(look.menu!!, 1)
        assertEquals(1, heard.get(5, TimeUnit.SECONDS))
        look.turn.unlock()
    }

    /** The setup's start already ran the move to the modern extension; this lets the next start run it again. */
    private fun clearMoveFlag() {
        app
            .getSharedPreferences("skins", 0)
            .edit()
            .remove("modern_migrated")
            .commit()
    }

    @Test
    fun `a worn classic file that holds a modern skin is moved at start and gives way`() {
        val junk = library.save(zipOf("skin.xml", "xml/player.xml"), "Bento.wal").id
        library.currentId = junk
        clearMoveFlag()

        val restarted = opsOn(WinampState(), modernOn = false)
        restarted.start()

        await { library.kindOf(junk) == SkinKind.MODERN && library.currentId == SkinEntry.BASE_ID }
        await { restarted.currentId == SkinEntry.BASE_ID && restarted.skin === restarted.baseSkin }
    }

    private companion object {
        /** Enough turns of the looper for a verb's own hop to IO and back. */
        const val SETTLE_ROUNDS = 20
    }
}
