// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.wal

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import nl.mattix.andamp.modernskin.fixtures.optionsItem
import nl.mattix.andamp.modernskin.skin.FakePlayer
import nl.mattix.andamp.modernskin.skin.SkinOptions
import nl.mattix.andamp.modernskin.skin.SkinRuntime
import nl.mattix.andamp.ui.menu.seedSkinOptions
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * A worn modern skin's options for Winamp's Options menu as its look publishes them for Skin
 * Settings: known once the skin has loaded, and a pick flipped on the engine thread, shown by the
 * next frame, saved and put back as the skin's other settings are.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WalLookSkinOptionsTest {
    @get:Rule
    val dir = TemporaryFolder()

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val prefs = app.getSharedPreferences(WalSettings.PREFS, Context.MODE_PRIVATE)
    private val looks = ArrayList<WalLook>()
    private val phone = WalFit(1080, 2000, 3f, sideways = false)

    @Before
    fun clearSettings() {
        prefs.edit().clear().commit()
    }

    @After
    fun releaseAll() = looks.forEach { it.release(save = false) }

    /** A skin of one window with a play button. */
    private fun wal(): File =
        dir.newFile().apply {
            val player =
                """<container id="main" name="t" default_visible="1"><layout id="normal" w="200" h="100">""" +
                    """<button id="play" action="PLAY" x="10" y="10" w="20" h="20"/></layout></container>"""
            val skin = """<WinampAbstractionLayer version="0.8"><include file="player.xml"/></WinampAbstractionLayer>"""
            ZipOutputStream(outputStream()).use { zip ->
                for ((path, text) in mapOf("skin.xml" to skin, "player.xml" to player)) {
                    zip.putNextEntry(ZipEntry(path))
                    zip.write(text.toByteArray())
                    zip.closeEntry()
                }
            }
        }

    private fun look(id: String = "options${runs.incrementAndGet()}"): WalLook =
        WalLook(id, wal(), "Test", emptyMap(), app.cacheDir, WalSettings(app), FakePlayer()).also { looks += it }

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

    /** One option, "Drawer", made off as a skin's script makes it. */
    private fun drawer(rt: SkinRuntime) = rt.optionsItem().option("Drawer", "0")

    private fun WalLook.drawer() = skinOptions.entries.filterIsInstance<SkinOptions.Toggle>().single()

    /** A look fitted to the phone, loaded, with the option made. */
    private fun started(id: String? = null): WalLook {
        val look = if (id == null) look() else look(id)
        look.fit(phone)
        await("the first frame arrives") { look.layout != null }
        seedSkinOptions(look, ::drawer)
        return look
    }

    @Test(timeout = 20_000)
    fun `options are known after load`() {
        val look = look()
        assertEquals("none before it has loaded", SkinOptions.EMPTY, look.skinOptions)

        look.fit(phone)
        await("the first frame arrives") { look.layout != null }
        assertTrue("the skin lists none yet", look.skinOptions.entries.isEmpty())
        seedSkinOptions(look, ::drawer)

        assertEquals("Drawer", look.drawer().text)
        assertFalse(look.drawer().checked)
    }

    @Test(timeout = 20_000)
    fun `a toggle shows by the next frame, is saved and comes back on the next look`() {
        val look = started()

        look.toggleSkinOption(look.drawer().key)

        await("the next frame ticks it") { look.drawer().checked }
        ShadowLooper.idleMainLooper(WalLook.SAVE_DELAY_MS + 100, TimeUnit.MILLISECONDS)
        await("it is saved") { prefs.getString(look.id, null)?.contains("\"Drawer\":\"1\"") == true }
        look.release(save = false)

        val again = started(look.id)
        assertTrue("the saved value wins over the script's default", again.drawer().checked)
    }

    @Test(timeout = 20_000)
    fun `a toggle after release saves nothing`() {
        val look = started()
        val key = look.drawer().key
        val engineThread = Thread.getAllStackTraces().keys.single { it.name == "wal-${look.id}" }

        look.release(save = true)
        engineThread.join(5_000)
        assertFalse("the engine thread ended", engineThread.isAlive)
        val kept = prefs.getString(look.id, null)

        look.toggleSkinOption(key)
        ShadowLooper.idleMainLooper(WalLook.SAVE_DELAY_MS * 2, TimeUnit.MILLISECONDS)

        assertEquals("what the release saved stays", kept, prefs.getString(look.id, null))
        assertNull(look.layout)
        assertEquals(SkinOptions.EMPTY, look.skinOptions)
    }

    private companion object {
        val runs = AtomicInteger()
    }
}
