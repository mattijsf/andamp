// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import nl.mattix.andamp.backend.mock.MockBackend
import nl.mattix.andamp.skin.BundledSkins
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A picked skin across relaunches: pick a skin, relaunch (a fresh ViewModel), and the same
 * skin comes back; reset, and the base skin comes back. ViewModel coroutines hop between
 * Main and IO; [await] idles the Robolectric main looper so Main continuations run while
 * real IO threads make progress.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE) // skin decode goes through BitmapFactory
@Config(sdk = [35])
class SkinPersistenceTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val library = SkinLibrary(app)
    private val baseBytes by lazy { app.assets.open("skins/AndAmp Light.wsz").use { it.readBytes() } }

    private fun vm(): WinampViewModel =
        WinampViewModel(
            app,
            createBackend = { scope -> MockBackend(FakeTracks.tracks, scope) },
            presetStore = InMemoryEqPresetStore(),
        )

    private fun await(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition() && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        assertTrue("the condition holds within 10 s", condition())
    }

    private fun awaitSkin(vm: WinampViewModel): String {
        await { vm.skin != null }
        return vm.skin!!.name
    }

    @Test
    fun `a picked skin survives a relaunch and reset brings the base skin back`() {
        val first = vm()
        val baseName = awaitSkin(first)

        // pick: the AndAmp Light skin's bytes under a custom name stand in for any .wsz
        first.skinOps.load(baseBytes.inputStream(), "MyCustom.wsz")
        await { first.skin?.name == "MyCustom.wsz" }
        await { library.currentId != SkinEntry.BASE_ID }
        val id = library.currentId

        // the content round-trips as well as the name
        assertTrue(
            "the stored bytes match the picked skin",
            library.open(id)!!.use { it.readBytes() }.contentEquals(baseBytes),
        )
        assertEquals("MyCustom.wsz", library.nameOf(id))

        // relaunch
        assertEquals("MyCustom.wsz", awaitSkin(vm()))

        // reset to base
        val second = vm()
        awaitSkin(second)
        second.skinOps.reset()
        assertEquals(baseName, second.skin!!.name)
        await { library.currentId == SkinEntry.BASE_ID }

        // relaunch again: base skin, though the library keeps the skin on file
        assertEquals(baseName, awaitSkin(vm()))
        assertTrue("reset keeps the library entry", library.open(id) != null)
    }

    @Test
    fun `an oversized pick is not stored`() {
        val oversized = ByteArray(8 * 1024 * 1024 + 1)
        val vm = vm()
        awaitSkin(vm)
        vm.skinOps.load(oversized.inputStream(), "huge-mispick.mp4")
        // a following valid pick waits behind it on SkinOps's `lock`, so awaiting this one
        // shows the oversized load has finished
        vm.skinOps.load(baseBytes.inputStream(), "After.wsz")
        await { vm.skin?.name == "After.wsz" }

        assertEquals("After.wsz", library.nameOf(library.currentId))
        assertTrue(library.list().none { it.name == "huge-mispick.mp4" })
    }

    @Test
    fun `garbage bytes never apply or land in the library`() {
        val vm = vm()
        awaitSkin(vm)

        vm.skinOps.load(ByteArray(100) { it.toByte() }.inputStream(), "garbage.wsz")
        vm.skinOps.load(baseBytes.inputStream(), "Real.wsz")
        await { vm.skin?.name == "Real.wsz" }

        assertTrue(library.list().none { it.name == "garbage.wsz" })
    }

    @Test
    fun `a skin that parses but cannot be stored still applies this session`() {
        // filesDir/skins as a regular FILE makes every library write fail
        val blocker = java.io.File(app.filesDir, "skins")
        blocker.deleteRecursively()
        blocker.parentFile?.mkdirs()
        blocker.writeText("in the way")

        val vm = vm()
        awaitSkin(vm)
        vm.skinOps.load(baseBytes.inputStream(), "Unsaved.wsz")

        await { vm.skin?.name == "Unsaved.wsz" } // applied despite the storage failure
        // nothing was written: what the library lists is what ships with the app
        assertEquals(BundledSkins.all.map { it.id }, SkinLibrary(app).list().map { it.id })
        // no library row is marked as the current skin
        assertEquals(SkinOps.UNSTORED_ID, vm.skinOps.currentId)
        blocker.delete()
    }
}
