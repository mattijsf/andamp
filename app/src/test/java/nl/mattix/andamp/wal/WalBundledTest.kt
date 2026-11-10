// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.wal

import android.app.Application
import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.test.core.app.ApplicationProvider
import nl.mattix.andamp.skin.BundledSkins
import nl.mattix.andamp.skin.LiveSkins
import nl.mattix.andamp.skin.SkinDist
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * The bundled skins as files for the engine: a copy of the shipped file without a palette, one
 * file per palette for a live skin, and only the newest two of a skin kept.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WalBundledTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    @Volatile
    private var palette: ColorScheme? = null

    private val live = LiveSkins { _, _ -> palette }

    @Before
    fun clean() {
        File(app.noBackupFilesDir, "wal-bundled").deleteRecursively()
    }

    private fun tinted(argb: Long) = SkinDist.baselineScheme("dark").copy(primary = Color(argb))

    @Test
    fun `without a palette a bundled skin is the file it ships, written once`() {
        val file = WalBundled.fileOf(app, BundledSkins.SPOT, live)
        val shipped = app.assets.open(BundledSkins.SPOT.asset).use { it.readBytes() }
        assertArrayEquals(shipped, file.readBytes())
        file.setLastModified(0)

        assertEquals(file, WalBundled.fileOf(app, BundledSkins.SPOT, live))
        assertEquals("not written again", 0L, file.lastModified())
    }

    @Test
    fun `a live skin gets a file per palette, and only the newest two stay`() {
        palette = tinted(0xFF7A1FA2)
        val first = WalBundled.fileOf(app, BundledSkins.BASE, live)
        first.setLastModified(1_000)
        assertEquals("the same palette, the same file", first, WalBundled.fileOf(app, BundledSkins.BASE, live))

        palette = tinted(0xFF1FA27A)
        val second = WalBundled.fileOf(app, BundledSkins.BASE, live)
        second.setLastModified(2_000)
        assertNotEquals(first, second)
        assertTrue("the one before stays for a look still showing it", first.isFile)

        palette = tinted(0xFFA27A1F)
        val third = WalBundled.fileOf(app, BundledSkins.BASE, live)

        assertFalse("the oldest goes", first.isFile)
        assertTrue(second.isFile && third.isFile)
        assertTrue("named for the skin and its palette", third.name.startsWith("${BundledSkins.BASE.id}-live-"))
    }
}
