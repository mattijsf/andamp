// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.wal

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** The adb check runs each skin in its folder once, leaves a picture beside it, and carries on where it stopped. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE) // the windows are drawn into a real Bitmap
class WalCheckTest {
    @get:Rule
    val dir = TemporaryFolder()

    private val app = ApplicationProvider.getApplicationContext<Application>()

    private fun wal(
        name: String,
        player: String =
            """<container id="main" name="t" default_visible="1"><layout id="normal" w="40" h="20">""" +
                """<button id="play" action="PLAY" x="0" y="0" w="20" h="20"/></layout></container>""",
        files: Map<String, ByteArray> = emptyMap(),
    ): File =
        dir.newFile(name).apply {
            ZipOutputStream(outputStream()).use { zip ->
                val skinXml = """<WinampAbstractionLayer version="0.8"><include file="player.xml"/></WinampAbstractionLayer>"""
                val entries = mapOf("skin.xml" to skinXml.toByteArray(), "player.xml" to player.toByteArray()) + files
                for ((path, bytes) in entries) {
                    zip.putNextEntry(ZipEntry(path))
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
        }

    /** A picture of one [color], as a skin's PNG. */
    private fun solid(color: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    private fun png(name: String) = File(dir.root, "$name.png")

    private fun run(
        budgetMs: Long = 60_000,
        fresh: Boolean = false,
        build: String = "1",
    ) = WalCheck.run(dir.root, app.cacheDir, budgetMs, fresh, build)

    @Test
    fun `each skin gets a picture beside it, and the next check does them all again`() {
        wal("One.wal")
        wal("Two.wal")

        assertTrue(run())
        assertTrue(png("One").exists() && png("Two").exists())

        png("One").delete()
        png("Two").delete()
        assertTrue(run())
        assertTrue(png("One").exists() && png("Two").exists())
    }

    @Test
    fun `a run whose time is up stops between skins, and the next check carries on`() {
        wal("One.wal")
        wal("Two.wal")

        assertFalse("the first skin uses up a budget of 0", run(budgetMs = 0))
        assertTrue(png("One").exists())
        assertFalse(png("Two").exists())

        png("One").delete()
        assertTrue(run())
        assertFalse("the finished skin is not checked again", png("One").exists())
        assertTrue(png("Two").exists())
    }

    @Test
    fun `a new build, a replaced skin or fresh starts the run over`() {
        wal("One.wal")
        wal("Two.wal")
        assertFalse(run(budgetMs = 0))

        png("One").delete()
        assertFalse(run(budgetMs = 0, build = "2"))
        assertTrue("a new build checks the first skin again", png("One").exists())

        png("One").delete()
        assertFalse(run(budgetMs = 0, build = "2", fresh = true))
        assertTrue("fresh checks the first skin again", png("One").exists())

        png("One").delete()
        File(dir.root, "One.wal").appendBytes(ByteArray(1))
        assertFalse(run(budgetMs = 0, build = "2"))
        assertTrue("a replaced skin is checked again", png("One").exists())
    }

    @Test
    fun `a skin is checked as the player readies it, with the visualizer closed`() {
        // a visualizer button, lit while the visualizer is open, and the skin's visualizer window open at the start
        wal(
            "Vis.wal",
            player =
                """<elements><bitmap id="off" file="off.png"/><bitmap id="on" file="on.png"/></elements>""" +
                    """<container id="main" name="t" default_visible="1"><layout id="normal" w="40" h="20">""" +
                    """<button id="vis" action="TOGGLE" param="avs" x="0" y="0" w="20" h="20" image="off" activeimage="on"/>""" +
                    """</layout></container>""" +
                    """<container id="avs" name="Visualization" default_visible="1">""" +
                    """<layout id="normal" w="40" h="20"/></container>""",
            files = mapOf("off.png" to solid(Color.BLUE), "on.png" to solid(Color.RED)),
        )

        assertTrue(run())

        val drawn = BitmapFactory.decodeFile(png("Vis").path)
        assertEquals("the visualizer is the app's, closed: the button is not lit", Color.BLUE, drawn.getPixel(10, 10))
    }

    @Test
    fun `an empty folder is done at once`() {
        assertTrue(run())
    }
}
