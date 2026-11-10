// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import android.app.Application
import android.os.Looper
import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import nl.mattix.andamp.skin.BundledSkins
import nl.mattix.andamp.skin.LiveSkins
import nl.mattix.andamp.skin.SkinDist
import nl.mattix.andamp.wal.WalFit
import nl.mattix.andamp.wal.WalLook
import nl.mattix.andamp.wal.WalSettings
import nl.mattix.walkit.FakePlayer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A classic skin is drawn by walkit through a look of its file, over the classic skin Andamp
 * still decodes for its menus: the stored file of the listener's own, a file written for a bundled
 * one, in the phone's palette for a live one. A skin whose look fails is drawn by Andamp's own
 * renderer.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class SkinOpsClassicNativeTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val state = WinampState()
    private lateinit var library: SkinLibrary
    private lateinit var ops: SkinOps
    private lateinit var mine: String

    /** The phone's palette for the live skins, or null for none. */
    @Volatile
    private var palette: ColorScheme? = null

    private val opened = CopyOnWriteArrayList<WalLook>()

    /** Whether making a look throws, as one of a broken file can. */
    @Volatile
    private var openFails = false

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
        java.io.File(app.noBackupFilesDir, "wal-bundled").deleteRecursively()
        library = SkinLibrary(app)
        mine = library.save(app.assets.open(BundledSkins.SPOT.asset).use { it.readBytes() }, "Mine.wsz").id
        library.currentId = mine
        ops =
            SkinOps(
                app,
                state,
                CoroutineScope(SupervisorJob() + Dispatchers.Main),
                library,
                modernOn = { false },
                openModern = { id, file, name, siblings ->
                    // the base still opens: it is what goes on in a broken skin's place
                    check(!openFails || id == BundledSkins.BASE.id) { "no look" }
                    WalLook(id, file, name, siblings, app.cacheDir, WalSettings(app), FakePlayer()).also { opened += it }
                },
                live = LiveSkins { _, _ -> palette },
            )
        ops.start()
        await { ops.skin != null && ops.currentId == mine }
    }

    @After
    fun releaseLooks() = opened.forEach { it.release(save = false) }

    @Test
    fun `a classic skin of the listener's own is drawn by the engine`() {
        val look = ops.wal
        assertNotNull("a look over the classic skin", look)
        assertEquals(mine, look!!.id)
        assertNotNull("the classic skin is still decoded for the menus", ops.skin)
        look.fit(WalFit(1080, 2000, 3f, sideways = false))
        await { look.layout != null }
    }

    @Test
    fun `a classic skin picked is drawn by the engine at once`() {
        var stored: String? = null
        val bytes = app.assets.open(BundledSkins.LIGHT.asset).use { it.readBytes() }
        ops.load(bytes.inputStream(), "Picked.wsz") { stored = it }
        await { stored != null && ops.currentId == stored }
        assertEquals(stored, ops.wal?.id)
    }

    @Test
    fun `a classic skin whose look cannot be made gives way to the base, drawn by the engine, and stays chosen`() {
        openFails = true
        var stored: String? = null
        val bytes = app.assets.open(BundledSkins.LIGHT.asset).use { it.readBytes() }
        ops.load(bytes.inputStream(), "Broken look.wsz") { stored = it }
        await { stored != null && ops.wal?.id == BundledSkins.BASE.id }
        assertEquals("the base is ticked", BundledSkins.BASE.id, ops.currentId)
        assertEquals("the choice stays for the next start", stored, library.currentId)
    }

    @Test
    fun `a classic skin that stops working gives way to the base and says so`() {
        val look = ops.wal ?: error("a look")
        look.onFailed?.invoke(true)
        await { ops.wal?.id == BundledSkins.BASE.id }
        assertEquals(BundledSkins.BASE.id, ops.currentId)
        assertEquals("the choice stays", mine, library.currentId)
        assertNotNull("a notice says it stopped", state.prompt)
    }

    @Test
    fun `a bundled skin is drawn by the engine from a copy of the file it ships`() {
        ops.apply(BundledSkins.LIGHT.id)
        await { ops.currentId == BundledSkins.LIGHT.id && ops.wal?.id == BundledSkins.LIGHT.id }
        val shipped = app.assets.open(BundledSkins.LIGHT.asset).use { it.readBytes() }
        assertArrayEquals(shipped, ops.wal!!.file.readBytes())
    }

    @Test
    fun `a live bundled skin is drawn in the phone's palette, and a new palette gives a new look`() {
        palette = SkinDist.baselineScheme("dark")
        ops.apply(BundledSkins.BASE.id)
        await { ops.wal?.id == BundledSkins.BASE.id }
        val first = ops.wal!!

        palette = SkinDist.baselineScheme("dark").copy(primary = Color(0xFF7A1FA2))
        ops.paletteChanged()

        await { ops.wal != null && ops.wal !== first }
        assertEquals(BundledSkins.BASE.id, ops.wal!!.id)
        assertNotEquals("another palette, another file", first.file, ops.wal!!.file)
        await { first.released }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        do {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(10)
        } while (System.currentTimeMillis() < deadline)
        fail("the condition holds within 10 s")
    }
}
