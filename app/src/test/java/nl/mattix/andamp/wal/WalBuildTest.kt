// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.wal

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeoutException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * A modern skin loaded off screen before it is added: the name it loads under, a skin that does
 * not open, and the time it may take.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WalBuildTest {
    @get:Rule
    val dir = TemporaryFolder()

    private val app = ApplicationProvider.getApplicationContext<Application>()

    private fun wal(
        name: String,
        skinXml: String = """<WinampAbstractionLayer version="0.8"><include file="player.xml"/></WinampAbstractionLayer>""",
    ): File =
        dir.newFile(name).apply {
            ZipOutputStream(outputStream()).use { zip ->
                val entries =
                    mapOf(
                        "skin.xml" to skinXml,
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

    @Test
    fun `a skin loads under the name it is given`() {
        assertEquals("Bento", WalBuild.load(wal("a.wal"), "Bento", app.cacheDir))
    }

    @Test
    fun `with no name given, a skin that names itself nowhere loads under none`() {
        assertEquals("", WalBuild.load(wal("b.wal"), "", app.cacheDir))
    }

    @Test
    fun `a skin whose zip cannot be opened throws`() {
        val whole = wal("c.wal")
        // without its directory, at the end, the file does not open as a zip
        whole.writeBytes(whole.readBytes().let { it.copyOf(it.size - 30) })

        assertThrows(IOException::class.java) { WalBuild.load(whole, "Cut", app.cacheDir) }
    }

    @Test
    fun `what the load throws is thrown`() {
        val thrown = assertThrows(IllegalStateException::class.java) { WalBuild.within(1_000) { error("no skin.xml") } }

        assertEquals("no skin.xml", thrown.message)
    }

    @Test
    fun `a load that takes longer than it may throws`() {
        assertThrows(TimeoutException::class.java) { WalBuild.within(50) { Thread.sleep(5_000) } }
    }
}
