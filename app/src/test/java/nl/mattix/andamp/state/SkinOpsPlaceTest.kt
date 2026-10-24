// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import nl.mattix.andamp.modernskin.skin.FakePlayer
import nl.mattix.andamp.skin.BundledSkins
import nl.mattix.andamp.wal.WalLook
import nl.mattix.andamp.wal.WalMove
import nl.mattix.andamp.wal.WalSettings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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
 * Where the listener moved a modern skin stays with the skin: through the next start and a music
 * source's skin worn over it, until the listener picks the skin again from Skins.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class SkinOpsPlaceTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var library: SkinLibrary
    private lateinit var ops: SkinOps

    /** Every modern look opened, in order. */
    private val opened = CopyOnWriteArrayList<WalLook>()

    private val moved = WalMove(-150, 300, 600, 200)

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
        library.currentId = BundledSkins.LIGHT.id
        ops = opsOn(WinampState())
        ops.start()
        await { ops.skin != null && ops.currentId == BundledSkins.LIGHT.id }
    }

    @After
    fun releaseLooks() = opened.forEach { it.release(save = false) }

    private fun opsOn(state: WinampState) =
        SkinOps(
            app,
            state,
            CoroutineScope(SupervisorJob() + Dispatchers.Main),
            library,
            modernOn = { true },
            openModern = { id, file, name, siblings ->
                WalLook(id, file, name, siblings, app.cacheDir, WalSettings(app), FakePlayer()).also { opened += it }
            },
        )

    /** Idles the looper before each look at the condition, as SkinOpsWearTest does. */
    private fun await(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        do {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(10)
        } while (System.currentTimeMillis() < deadline)
        fail("the condition holds within 10 s")
    }

    private fun wornModern(): WalLook {
        ops.apply(library.addModern(app, "Bento"))
        await { ops.wal != null }
        return ops.wal!!
    }

    @Test
    fun `a moved modern skin keeps its place at the next start`() {
        val look = wornModern()
        look.places[false] = moved

        val next = opsOn(WinampState())
        next.start()
        await { next.wal != null }

        assertEquals(moved, next.wal!!.places[false])
    }

    @Test
    fun `picking the worn modern skin again from Skins puts it back where the engine places it`() {
        val look = wornModern()
        look.places[false] = moved
        look.places[true] = WalMove(30, 0, 900, 400)

        ops.apply(look.id)

        await { look.places[false] == null && look.places[true] == null }
        assertSame("the same look", look, ops.wal)
        assertNull(WalSettings(app).place(look.id, sideways = false))
    }

    @Test
    fun `picking a moved modern skin from another skin puts it on where the engine places it`() {
        val modern = library.addModern(app, "Bento")
        WalSettings(app).keepPlace(modern, sideways = false, move = moved)

        ops.apply(modern)

        await { ops.wal != null }
        assertNull(ops.wal!!.places[false])
        assertNull(WalSettings(app).place(modern, sideways = false))
    }

    @Test
    fun `a source's skin over a moved modern skin leaves its place`() {
        val look = wornModern()
        look.places[false] = moved

        ops.wear(BundledSkins.SPOT.id)
        await { ops.currentId == BundledSkins.SPOT.id }
        ops.wear(null)
        await { ops.currentId == look.id }

        assertEquals(moved, ops.wal!!.places[false])
    }
}
