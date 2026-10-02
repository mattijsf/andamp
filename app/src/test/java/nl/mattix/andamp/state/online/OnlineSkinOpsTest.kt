// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state.online

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import nl.mattix.andamp.state.SkinEntry
import nl.mattix.andamp.state.SkinLibrary
import nl.mattix.andamp.state.SkinOps
import nl.mattix.andamp.state.WinampState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.IOException

/**
 * Installing a museum skin puts it on screen and records the museum's hash against the
 * library's id; uninstalling takes both away. That pairing is how a row can say "Uninstall"
 * without downloading anything.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE) // installing parses the skin, which decodes art
@Config(sdk = [35])
class OnlineSkinOpsTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private lateinit var library: SkinLibrary
    private lateinit var installed: InstalledSkins
    private lateinit var skinOps: SkinOps
    private lateinit var state: WinampState

    private val museumSkin =
        OnlineSkin(
            md5 = "d770a634cc1ab252cfb16c64f4f5f616",
            filename = "Zelda_Amp_3.wsz",
            screenshotUrl = "https://example.invalid/shot.png",
            downloadUrl = "https://example.invalid/skin.wsz",
            museumUrl = null,
            nsfw = false,
        )

    /** The bundled skin's bytes stand in for whatever the museum would send. */
    private fun skinBytes(): ByteArray = app.assets.open("skins/AndAmp Light.wsz").use { it.readBytes() }

    private fun ops(download: (String) -> ByteArray) =
        OnlineSkinOps(skinOps, library, installed, scope, io = Dispatchers.Unconfined, download = download)

    private fun idle() = shadowOf(android.os.Looper.getMainLooper()).idle()

    /**
     * Waits for asynchronous work: applying a skin parses it on the IO dispatcher, which idling
     * the main looper does not wait for.
     */
    private fun awaitUntil(
        what: String,
        done: () -> Boolean,
    ) {
        val until = System.currentTimeMillis() + TIMEOUT_MS
        while (System.currentTimeMillis() < until) {
            idle()
            if (done()) return
            Thread.sleep(POLL_MS)
        }
        idle()
        assertTrue(what, done())
    }

    @Before
    fun setUp() {
        File(app.filesDir, "skins").deleteRecursively()
        app
            .getSharedPreferences("online_skins", 0)
            .edit()
            .clear()
            .commit()
        library = SkinLibrary(app)
        installed = InstalledSkins(app)
        state = WinampState()
        skinOps = SkinOps(app, state, scope, library)
        skinOps.start()
        idle()
    }

    @Test
    fun `installing puts the skin on screen and files it under the museum's hash`() {
        val bytes = skinBytes()
        val ops = ops { bytes }

        ops.install(museumSkin)
        awaitUntil("the installed skin is applied") { skinOps.currentId == library.idFor(bytes) }

        assertTrue("the row offers Uninstall", ops.isInstalled(museumSkin.md5))
        assertEquals(library.idFor(bytes), installed.libraryId(museumSkin.md5))
        assertEquals("the skin is applied", library.idFor(bytes), skinOps.currentId)
        assertTrue("the skin is kept in the library", state.skinEntries.any { it.id == library.idFor(bytes) })
    }

    @Test
    fun `a download that fails leaves nothing behind but a message`() {
        val ops = ops { throw IOException("no network") }

        ops.install(museumSkin)
        idle()

        assertTrue(!ops.isInstalled(museumSkin.md5))
        assertNull(installed.libraryId(museumSkin.md5))
        assertEquals("Could not download Zelda_Amp_3.wsz", ops.message)
    }

    /**
     * The tick is recorded on the outcome: [SkinOps.load] swallows a file that will not parse
     * and reports a null id, and a null id leaves no record.
     */
    @Test
    fun `bytes that are not a skin leave no tick behind`() {
        val ops = ops { ByteArray(64) { it.toByte() } }

        ops.install(museumSkin)
        awaitUntil("a message is reported") { ops.message != null }

        assertTrue("the tile stays unticked", !ops.isInstalled(museumSkin.md5))
        assertNull(installed.libraryId(museumSkin.md5))
        assertEquals("Could not read Zelda_Amp_3.wsz", ops.message)
    }

    /** The Skin Manager's REM column removes the same file. */
    @Test
    fun `a skin removed in the manager unticks its museum tile`() {
        val bytes = skinBytes()
        val ops = ops { bytes }
        skinOps.onRemoved = { id -> ops.forgetId(id) }
        ops.install(museumSkin)
        awaitUntil("the installed skin is applied") { skinOps.currentId == library.idFor(bytes) }

        skinOps.remove(library.idFor(bytes))
        awaitUntil("the file leaves the library") { state.skinEntries.none { it.id == library.idFor(bytes) } }

        assertTrue("the museum tile is unticked", !ops.isInstalled(museumSkin.md5))
        assertNull(installed.libraryId(museumSkin.md5))
    }

    @Test
    fun `uninstalling removes the file, forgets the pairing and falls back`() {
        val bytes = skinBytes()
        val ops = ops { bytes }
        ops.install(museumSkin)
        awaitUntil("the installed skin is applied") { skinOps.currentId == library.idFor(bytes) }

        ops.uninstall(museumSkin)
        // falling back and dropping the entry are two updates; wait for both
        awaitUntil("the player falls back and drops the entry") {
            skinOps.currentId == SkinEntry.BASE_ID && state.skinEntries.none { it.id == library.idFor(bytes) }
        }

        assertTrue("the row offers Install", !ops.isInstalled(museumSkin.md5))
        assertNull(installed.libraryId(museumSkin.md5))
        assertEquals("the player falls back to the base skin", SkinEntry.BASE_ID, skinOps.currentId)
        assertTrue(state.skinEntries.none { it.id == library.idFor(bytes) })
    }

    @Test
    fun `what was installed is known again after a restart`() {
        val bytes = skinBytes()
        ops { bytes }.install(museumSkin)
        awaitUntil("the install is recorded") { InstalledSkins(app).libraryId(museumSkin.md5) != null }

        val afterRestart = OnlineSkinOps(skinOps, library, InstalledSkins(app), scope, Dispatchers.Unconfined) { bytes }

        assertTrue(afterRestart.isInstalled(museumSkin.md5))
    }

    @Test
    fun `tapping install twice downloads once`() {
        var downloads = 0
        val bytes = skinBytes()
        val ops =
            ops {
                downloads++
                bytes
            }

        ops.install(museumSkin)
        ops.install(museumSkin)
        idle()

        assertEquals(1, downloads)
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
        const val POLL_MS = 20L
    }
}
