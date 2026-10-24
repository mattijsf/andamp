// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.compose.runtime.snapshots.Snapshot
import androidx.test.core.app.ApplicationProvider
import nl.mattix.andamp.skin.BundledSkins
import nl.mattix.andamp.ui.window.testViewModel
import nl.mattix.andamp.wal.WalGate
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Double Size while a modern skin is on screen: the player shows it off, since a modern skin has
 * no Double Size, and the listener's setting stays stored for the next classic skin.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE) // the skins decode through BitmapFactory
@Config(sdk = [35])
class ModernDoubleSizeTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val stored = app.getSharedPreferences("doublesize", Context.MODE_PRIVATE)
    private val made = ArrayList<WinampViewModel>()

    @Before
    fun setUp() {
        // modern skins on, as a debug build reads them with no switch written
        WalGate.forget()
        WalGate.file(app).delete()
        app
            .getSharedPreferences("skins", 0)
            .edit()
            .clear()
            .commit()
        File(app.filesDir, "skins").deleteRecursively()
        stored.edit().putBoolean("on", true).commit()
    }

    @After
    fun tearDown() {
        made.forEach { it.skinOps.wal?.release(save = false) }
        WalGate.forget()
    }

    private fun await(
        what: String,
        condition: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + 10_000
        do {
            // what the frame clock does in the app: snapshot writes reach the view model's collectors
            Snapshot.sendApplyNotifications()
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(10)
        } while (System.currentTimeMillis() < deadline)
        fail(what)
    }

    @Test
    fun `a modern skin on screen shows Double Size off, and a classic skin brings it back`() {
        val library = SkinLibrary(app)
        library.choose(library.addModern(app, "Bento"))

        // the Double Size store hands its setting over while it is made, before the skins exist
        val vm = testViewModel().also { made += it }

        await("the modern skin is on screen") { vm.skinOps.wal != null }
        await("Double Size shows off") { !vm.state.doubleSize }
        assertTrue("the setting stays", vm.doubleSize.on)
        assertTrue(stored.getBoolean("on", false))

        vm.skinOps.apply(BundledSkins.LIGHT.id)

        await("Double Size is back with the classic skin") { vm.skinOps.wal == null && vm.state.doubleSize }
        assertTrue(stored.getBoolean("on", false))
    }

    @Test
    fun `with a classic skin, Double Size stored on shows on`() {
        val vm = testViewModel().also { made += it }

        await("the skin is on") { vm.skin != null }

        assertTrue(vm.state.doubleSize)
        assertFalse(vm.skinOps.wal != null)
    }
}
