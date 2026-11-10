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
 * The new classic renderer: with it on, a classic skin is drawn by walkit through a look of its
 * file, over the classic skin Andamp still decodes for its menus: the stored file of the
 * listener's own, a file written for a bundled one, in the phone's palette for a live one. With it
 * off, Andamp's own renderer draws it.
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

    /** The new classic renderer's switch, as [SkinOps] reads it. */
    private var native = true

    /** The phone's palette for the live skins, or null for none. */
    @Volatile
    private var palette: ColorScheme? = null

    private val opened = CopyOnWriteArrayList<WalLook>()

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
                    WalLook(id, file, name, siblings, app.cacheDir, WalSettings(app), FakePlayer()).also { opened += it }
                },
                live = LiveSkins { _, _ -> palette },
                classicNative = { native },
            )
        ops.start()
        await { ops.skin != null && ops.currentId == mine }
    }

    @After
    fun releaseLooks() = opened.forEach { it.release(save = false) }

    @Test
    fun `a classic skin of the listener's own is drawn by the engine while the switch is on`() {
        val look = ops.wal
        assertNotNull("a look over the classic skin", look)
        assertEquals(mine, look!!.id)
        assertNotNull("the classic skin is still decoded for the menus", ops.skin)
        look.fit(WalFit(1080, 2000, 3f, sideways = false))
        await { look.layout != null }
    }

    @Test
    fun `switched off, Andamp's own renderer draws the skin again`() {
        val look = ops.wal ?: error("a look")
        native = false
        ops.classicRendererChanged()
        await { ops.wal == null }
        assertEquals(mine, ops.currentId)
        await { look.released }
        native = true
        ops.classicRendererChanged()
        await { ops.wal != null }
    }

    @Test
    fun `a classic skin picked while the switch is on is drawn by the engine at once`() {
        var stored: String? = null
        val bytes = app.assets.open(BundledSkins.LIGHT.asset).use { it.readBytes() }
        ops.load(bytes.inputStream(), "Picked.wsz") { stored = it }
        await { stored != null && ops.currentId == stored }
        assertEquals(stored, ops.wal?.id)
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
