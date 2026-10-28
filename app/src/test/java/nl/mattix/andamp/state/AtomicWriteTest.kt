// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.io.File
import java.io.IOException

/**
 * How a playlist, a station list or the bookmarks are saved, so a phone that switches off mid-save
 * comes back with the old list or the new one, whole: the text is written and flushed under a
 * scratch name, then renamed over the file.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AtomicWriteTest {
    @get:Rule val folder = TemporaryFolder()

    private val dir get() = folder.root

    @Test
    fun `the new text is flushed whole before the rename puts it in place of the old`() {
        val file = File(dir, "queue.m3u").apply { writeText("old") }
        val seen = ArrayList<String>()

        file.writeAtomically("the new list") { channel ->
            val scratch = dir.listFiles()!!.single { it != file }
            seen += "scratch '${scratch.readText()}', stored '${file.readText()}'"
            channel.force(true)
        }

        assertEquals(listOf("scratch 'the new list', stored 'old'"), seen)
        assertEquals("the new list", file.readText())
        assertEquals("no scratch file is left", listOf("queue.m3u"), dir.list()!!.toList())
    }

    @Test
    fun `a save whose flush fails keeps the old text and leaves no scratch file`() {
        val file = File(dir, "queue.m3u").apply { writeText("old") }

        assertThrows(IOException::class.java) {
            file.writeAtomically("the new list") { throw IOException("the disk failed") }
        }

        assertEquals("old", file.readText())
        assertEquals(listOf("queue.m3u"), dir.list()!!.toList())
    }

    @Test
    fun `a write that stops partway deletes what it wrote`() {
        val part = File(dir, "part.tmp")

        assertThrows(IOException::class.java) {
            part.writeFlushed { out ->
                out.write(ByteArray(10))
                throw IOException("the disk is full")
            }
        }

        assertEquals(emptyList<String>(), dir.list()!!.toList())
    }

    @Test
    fun `a folder that cannot be flushed is logged, not thrown`() {
        dir.flushFolder { throw IOException("the folder was not flushed") }

        assertTrue(ShadowLog.getLogsForTag("AtomicWrite").any { it.throwable is IOException })
    }
}
