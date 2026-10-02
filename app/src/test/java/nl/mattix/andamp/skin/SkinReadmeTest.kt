// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.skin

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The note the artist packed with the skin.
 *
 * There is no rule about what the file is called, so these fix what counts as
 * one.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class SkinReadmeTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    private val base by lazy { SkinLoader.loadBase(app) }

    /** The bundled skin's art, plus whatever text files a case wants. */
    private fun skinCarrying(vararg text: Pair<String, String>): Skin {
        val bytes = app.assets.open("skins/AndAmp Light.wsz").use { it.readBytes() }
        val members = mutableMapOf<String, ByteArray>()
        java.util.zip.ZipInputStream(bytes.inputStream()).use { zip ->
            generateSequence { zip.nextEntry }.forEach { entry ->
                if (!entry.isDirectory) members[entry.name.substringAfterLast('/')] = zip.readBytes()
            }
        }
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            members.filterKeys { !it.endsWith(".txt", ignoreCase = true) }.forEach { (name, data) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(data)
                zip.closeEntry()
            }
            text.forEach { (name, body) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(body.toByteArray(Charsets.ISO_8859_1))
                zip.closeEntry()
            }
        }
        return SkinLoader.load(out.toByteArray().inputStream(), fallback = base, name = "readme-case")
    }

    @Test
    fun `a readme is what the archive says it is`() {
        val skin = skinCarrying("readme.txt" to "Made by someone, 1999.")

        assertEquals("Made by someone, 1999.", skin.readme)
    }

    @Test
    fun `a note named after the skin counts too`() {
        val skin = skinCarrying("Hexagon Amp.TXT" to "Hexagon Amp by A. N. Other")

        assertEquals("Hexagon Amp by A. N. Other", skin.readme)
    }

    @Test
    fun `the skin format's own text files are not notes`() {
        val skin = skinCarrying("pledit.txt" to "[Text]\nNormal=#00FF00", "viscolor.txt" to "0,0,0")

        assertNull(skin.readme)
    }

    @Test
    fun `a readme wins over any other text file`() {
        val skin = skinCarrying("aaa.txt" to "not this one", "readme.txt" to "this one")

        assertEquals("this one", skin.readme)
    }

    @Test
    fun `dos line endings are normalized`() {
        // a Compose Text draws the carriage return as a glyph of its own
        val skin = skinCarrying("readme.txt" to "one\r\ntwo\r\n")

        assertEquals("one\ntwo", skin.readme)
        assertTrue(skin.readme?.contains('\r') == false)
    }

    @Test
    fun `a blank note is no note`() {
        val skin = skinCarrying("readme.txt" to "   \r\n\r\n  ")

        assertNull(skin.readme)
    }

    @Test
    fun `a skin with no text file has no readme`() {
        assertNull(skinCarrying().readme)
    }
}
