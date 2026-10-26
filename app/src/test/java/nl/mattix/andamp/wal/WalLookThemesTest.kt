// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.wal

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import nl.mattix.andamp.modernskin.skin.FakePlayer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
 * A worn modern skin's color themes as its look publishes them for the Skins menu: known once the
 * skin has loaded, the worn one with them, and a pick from the menu put on, saved and put back as
 * a pick from the skin's theme list is.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WalLookThemesTest {
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

    /** A skin of one window with a play button, and a color theme per name in [themes], in that order. */
    private fun wal(themes: List<String> = listOf("One", "Two")): File =
        dir.newFile().apply {
            val player =
                themes.joinToString("") { """<gammaset id="$it"/>""" } +
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

    private fun look(
        file: File = wal(),
        id: String = "themes${runs.incrementAndGet()}",
    ): WalLook = WalLook(id, file, "Test", emptyMap(), app.cacheDir, WalSettings(app), FakePlayer()).also { looks += it }

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

    @Test(timeout = 20_000)
    fun `color themes are known once the skin loads, one per name ignoring case, in its theme list's order`() {
        val look = look(wal(listOf("beta", "Alpha", "ALPHA", "Gamma")))
        assertEquals("none before it has loaded", emptyList<String>(), look.themes)
        assertNull(look.theme)

        look.fit(phone)
        await("the first frame arrives") { look.layout != null }

        assertEquals(listOf("Alpha", "beta", "Gamma"), look.themes)
        assertEquals("the skin wears its first theme", "beta", look.theme)
        look.pickTheme("ALPHA")
        await("a name differing only by case puts on the one listed") { look.theme == "Alpha" }
    }

    @Test(timeout = 20_000)
    fun `a theme picked from the menu is worn by the next frame, saved once changes rest, and put back by the next look`() {
        val look = look()
        look.fit(phone)
        await("the first frame arrives") { look.layout != null }
        assertEquals("One", look.theme)

        look.pickTheme("Two")

        await("the next frame wears it") { look.theme == "Two" && look.engine?.frame?.themeKey == "Two" }
        ShadowLooper.idleMainLooper(WalLook.SAVE_DELAY_MS + 100, TimeUnit.MILLISECONDS)
        await("the theme is saved") { prefs.getString(look.id, null)?.contains("\"theme\":\"Two\"") == true }
        look.release(save = false)

        val again = look(id = look.id)
        again.fit(phone)
        await("the skin is on again") { again.layout != null }
        assertEquals("the restored theme", "Two", again.theme)
    }

    @Test(timeout = 20_000)
    fun `a theme picked before the skin starts is dropped, and one picked after release does nothing`() {
        val look = look()

        look.pickTheme("Two")
        look.fit(phone)
        await("the first frame arrives") { look.layout != null }
        assertEquals("One", look.theme)
        repeat(20) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        ShadowLooper.idleMainLooper(WalLook.SAVE_DELAY_MS * 2, TimeUnit.MILLISECONDS)
        assertNull("nothing changed, so nothing is saved", prefs.getString(look.id, null))

        look.release(save = false)
        look.pickTheme("Two")

        assertEquals(emptyList<String>(), look.themes)
        assertNull(look.theme)
    }

    private companion object {
        val runs = AtomicInteger()
    }
}
