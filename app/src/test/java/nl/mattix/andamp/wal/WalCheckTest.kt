// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.wal

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
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

    private fun wal(name: String): File =
        dir.newFile(name).apply {
            ZipOutputStream(outputStream()).use { zip ->
                val entries =
                    mapOf(
                        "skin.xml" to
                            """<WinampAbstractionLayer version="0.8"><include file="player.xml"/></WinampAbstractionLayer>""",
                        "player.xml" to
                            """<container id="main" name="t" default_visible="1"><layout id="normal" w="40" h="20">""" +
                            """<button id="play" action="PLAY" x="0" y="0" w="20" h="20"/></layout></container>""",
                    )
                for ((path, text) in entries) {
                    zip.putNextEntry(ZipEntry(path))
                    zip.write(text.toByteArray())
                    zip.closeEntry()
                }
            }
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
    fun `an empty folder is done at once`() {
        assertTrue(run())
    }
}
