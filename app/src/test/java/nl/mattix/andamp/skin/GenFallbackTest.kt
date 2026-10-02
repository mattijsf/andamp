// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.skin

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class GenFallbackTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    /** The bundled light skin with its GEN.BMP removed. */
    private fun skinWithoutGen(base: Skin): Skin {
        val bytes = app.assets.open("skins/AndAmp Light.wsz").use { it.readBytes() }
        val members = mutableMapOf<String, ByteArray>()
        java.util.zip.ZipInputStream(bytes.inputStream()).use { zip ->
            generateSequence { zip.nextEntry }.forEach { entry ->
                if (!entry.isDirectory) members[entry.name.substringAfterLast('/')] = zip.readBytes()
            }
        }
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            members.filterKeys { !it.equals("GEN.BMP", ignoreCase = true) }.forEach { (name, data) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(data)
                zip.closeEntry()
            }
        }
        return SkinLoader.load(out.toByteArray().inputStream(), fallback = base, name = "no-gen")
    }

    @Test
    fun `a skin without GEN_BMP borrows the fallback's frame and title font`() {
        // otherwise generic windows (Milkdrop, the skin manager) would draw
        // with no chrome
        val base = SkinLoader.loadBase(app)
        val skin = skinWithoutGen(base)
        assertNotNull("the frame sheet comes from the fallback", skin.getOrNull(Sheet.GEN))
        assertNotNull("the title font comes from the fallback", skin.genTitleFont)
        assertTrue(skin.hasGenWindow)
    }

    @Test
    fun `the bundled skin brings its own generic frame`() {
        val base = SkinLoader.loadBase(app)
        assertTrue(base.hasGenWindow)
        assertTrue(base.ownsGenArt)
    }

    @Test
    fun `a stub GEN_BMP does not count as the skin's own chrome`() {
        // a 1x1 placeholder holds neither the letter strips nor the frame art
        val base = SkinLoader.loadBase(app)
        val bytes = app.assets.open("skins/AndAmp Light.wsz").use { it.readBytes() }
        val members = mutableMapOf<String, ByteArray>()
        java.util.zip.ZipInputStream(bytes.inputStream()).use { zip ->
            generateSequence { zip.nextEntry }.forEach { entry ->
                if (!entry.isDirectory) members[entry.name.substringAfterLast('/')] = zip.readBytes()
            }
        }
        val stub =
            ByteArrayOutputStream().also { png ->
                android.graphics.Bitmap
                    .createBitmap(1, 1, android.graphics.Bitmap.Config.ARGB_8888)
                    .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, png)
            }
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            (members.filterKeys { !it.equals("GEN.BMP", ignoreCase = true) } + ("GEN.BMP" to stub.toByteArray()))
                .forEach { (name, data) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(data)
                    zip.closeEntry()
                }
        }
        val skin = SkinLoader.load(out.toByteArray().inputStream(), fallback = base, name = "stub-gen")

        assertTrue("a stub sheet does not count as the skin's own chrome", !skin.ownsGenArt)
        assertNotNull("the title font comes from the fallback", skin.genTitleFont)
    }
}
