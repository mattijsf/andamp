// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import nl.mattix.andamp.skin.BundledSkins
import nl.mattix.andamp.wal.WalFit
import nl.mattix.andamp.wal.WalLook
import nl.mattix.andamp.wal.WalSettings
import nl.mattix.walkit.FakePlayer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
 * The new classic renderer: with it on, a classic skin of the listener's own is drawn by walkit
 * through a look of its stored file, over the classic skin Andamp still decodes for its menus;
 * with it off, or for a bundled skin, Andamp's own renderer draws it.
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
    fun `a bundled skin keeps Andamp's own renderer`() {
        ops.apply(BundledSkins.LIGHT.id)
        await { ops.currentId == BundledSkins.LIGHT.id }
        assertNull(ops.wal)
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
