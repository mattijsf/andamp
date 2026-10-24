// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.wal

import android.app.Application
import androidx.compose.ui.unit.IntOffset
import androidx.test.core.app.ApplicationProvider
import nl.mattix.andamp.modernskin.skin.FakePlayer
import nl.mattix.andamp.modernskin.skin.FontDef
import nl.mattix.andamp.modernskin.skin.SkinFiles
import nl.mattix.andamp.modernskin.skin.SkinHost
import nl.mattix.andamp.modernskin.skin.SkinRuntime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What a skin kept comes back the next time it loads: its theme, its settings and its scripts' values. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WalSettingsTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    private val files =
        object : SkinFiles {
            private val m =
                mapOf(
                    "skin.xml" to
                        """<WinampAbstractionLayer version="0.8"><include file="player.xml"/></WinampAbstractionLayer>""",
                    "player.xml" to
                        """<gammaset id="Plain"/><gammaset id="Warm"><gammagroup id="text" value="200,0,0"/></gammaset>""" +
                        """<container id="main" name="t" default_visible="1"><layout id="normal" w="200" h="100"/></container>""",
                ).mapValues { it.value.toByteArray() }

            override fun read(path: String): ByteArray? = m[SkinFiles.normalize(path)]

            override fun names(): List<String> = m.keys.toList()
        }

    private val host =
        object : SkinHost {
            override fun imageSize(file: String): Pair<Int, Int>? = null

            override fun textWidth(
                text: String,
                font: FontDef?,
                size: Int,
            ) = 0

            override fun log(message: String) = Unit

            override fun invalidate() = Unit
        }

    private fun runtime() = SkinRuntime(files, host, FakePlayer())

    @Test
    fun `a skin's theme, settings and private values come back`() {
        val settings = WalSettings(app)
        val first = runtime().apply { load() }
        first.selectGammaSet("Warm")
        first.storedSettings["Animate Config Drawer"] = "0"
        first.privateStrings["bento/component"] = "Video"
        first.privateInts["bento/width"] = 412
        settings.save("bento", first)

        val again = runtime()
        settings.restoreBeforeLoad("bento", again)
        again.load()
        settings.restoreTheme("bento", again)

        assertEquals("Warm", again.currentGammaSet)
        assertEquals("0", again.storedSettings["Animate Config Drawer"])
        assertEquals("Video", again.privateStrings["bento/component"])
        assertEquals(412, again.privateInts["bento/width"])
    }

    @Test
    fun `each skin keeps its values apart, and a forgotten skin starts clean`() {
        val settings = WalSettings(app)
        val one = runtime().apply { load() }
        one.privateStrings["a/b"] = "one"
        settings.save("one", one)
        settings.save("two", runtime().apply { load() })

        val fresh = runtime()
        settings.restoreBeforeLoad("two", fresh)
        assertTrue("another skin's values stay with it", fresh.privateStrings.isEmpty())
        val first = runtime()
        settings.restoreBeforeLoad("one", first)
        assertEquals("one", first.privateStrings["a/b"])

        settings.forget("one")
        val forgotten = runtime()
        settings.restoreBeforeLoad("one", forgotten)
        assertTrue(forgotten.privateStrings.isEmpty())
    }

    @Test
    fun `where a skin was moved comes back after a restart, upright and sideways apart, until it is forgotten`() {
        WalSettings(app).keepPlace("bento", sideways = false, move = IntOffset(-150, 300))
        WalSettings(app).keepPlace("bento", sideways = true, move = IntOffset(40, -12))

        val again = WalSettings(app)
        assertEquals(IntOffset(-150, 300), again.place("bento", sideways = false))
        assertEquals(IntOffset(40, -12), again.place("bento", sideways = true))
        assertNull("another skin sits where the engine puts it", again.place("mmd3", sideways = false))

        again.keepPlace("bento", sideways = true, move = null)
        assertNull(again.place("bento", sideways = true))
        assertEquals(IntOffset(-150, 300), again.place("bento", sideways = false))

        again.forget("bento")
        assertNull(WalSettings(app).place("bento", sideways = false))
    }

    @Test
    fun `a skin's settings saved after it was moved leave the place as it was`() {
        val settings = WalSettings(app)
        settings.keepPlace("bento", sideways = false, move = IntOffset(0, 500))

        settings.save("bento", runtime().apply { load() })

        assertEquals(IntOffset(0, 500), settings.place("bento", sideways = false))
    }
}
