// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import nl.mattix.andamp.backend.mock.MockBackend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.random.Random

/**
 * Picked files that are not added: a modern skin (.wal) and a zip too large for a skin each end in
 * a notice that says why, and leave the library and the skin on screen as they were.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE) // the base skin decodes through BitmapFactory
@Config(sdk = [35])
class SkinPickTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val library = SkinLibrary(app)

    private fun vm(): WinampViewModel =
        WinampViewModel(
            app,
            createBackend = { scope -> MockBackend(FakeTracks.tracks, scope) },
            presetStore = InMemoryEqPresetStore(),
        )

    private fun await(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 20_000
        while (!condition() && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        assertTrue("the condition holds within 20 s", condition())
    }

    private fun started(): WinampViewModel = vm().also { vm -> await { vm.skin != null } }

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, bytes) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private val skinXml = "<WinampAbstractionLayer version=\"0.8\"/>".toByteArray()

    /** Bytes that do not compress, so a zip holding them is about as large. */
    private fun noise(size: Int) = Random(size).nextBytes(size)

    /** Loads [bytes] as a pick named [name] and returns the notice it ends in. */
    private fun pick(
        vm: WinampViewModel,
        bytes: InputStream,
        name: String,
    ): String {
        vm.skinOps.load(bytes, name)
        await { vm.state.prompt != null }
        assertNoCopyLeft()
        return vm.state.prompt!!.title
    }

    /** Loads [bytes] and checks that the pick ends with nothing to say. */
    private fun pickSilently(
        vm: WinampViewModel,
        bytes: InputStream,
        name: String,
    ) {
        val load = vm.skinOps.load(bytes, name)
        await { load.isCompleted }
        shadowOf(Looper.getMainLooper()).idle()
        assertNull("$name says nothing", vm.state.prompt)
        assertNull(vm.state.waitingNotice)
        assertNoCopyLeft()
    }

    private fun assertNoCopyLeft() {
        val left = app.cacheDir.listFiles { f -> f.name.startsWith("pick") }.orEmpty()
        assertTrue("the pick's copy is gone: ${left.map { it.name }}", left.isEmpty())
    }

    /** A stream of [size] bytes that starts with [head] and goes on in zeros, made as it is read. */
    private fun stream(
        size: Long,
        head: ByteArray = ByteArray(0),
    ): InputStream =
        object : InputStream() {
            var at = 0L

            override fun read(): Int {
                if (at >= size) return -1
                val b = if (at < head.size) head[at.toInt()].toInt() and 0xff else 0
                at++
                return b
            }

            override fun read(
                b: ByteArray,
                off: Int,
                len: Int,
            ): Int {
                if (at >= size) return -1
                val n = minOf(len.toLong(), size - at).toInt()
                for (i in 0 until n) b[off + i] = read().toByte()
                return n
            }
        }

    private val zipHead = byteArrayOf(0x50, 0x4b, 0x03, 0x04)

    @Test
    fun `a modern skin is not added, and a notice says why`() {
        val vm = started()
        val before = library.list()
        val onScreen = vm.skin!!.name

        val title = pick(vm, zip("skin.xml" to skinXml, "player.xml" to skinXml).inputStream(), "Bento.wal")

        assertEquals("“Bento.wal” is a modern skin", title)
        assertEquals("the notice has only its OK", null, vm.state.prompt!!.dismissLabel)
        assertEquals("nothing is stored", before, library.list())
        assertEquals("the skin on screen stays", onScreen, vm.skin!!.name)
        vm.state.prompt!!.onConfirm()
        assertNull(vm.state.prompt)
    }

    @Test
    fun `a modern skin kept in one folder of its zip is told apart too`() {
        val vm = started()

        val title = pick(vm, zip("Bento/skin.xml" to skinXml).inputStream(), "Bento.zip")

        assertEquals("“Bento.zip” is a modern skin", title)
    }

    @Test
    fun `a modern skin larger than a classic skin may be is still told apart`() {
        val vm = started()
        val big = zip("skin.xml" to skinXml, "art.png" to noise(SkinOps.MAX_SKIN_BYTES + 1024))

        assertEquals("“Big.wal” is a modern skin", pick(vm, big.inputStream(), "Big.wal"))
    }

    @Test
    fun `a zip too large for a skin says so`() {
        val vm = started()
        val before = library.list()
        val big = zip("main.bmp" to noise(SkinOps.MAX_SKIN_BYTES + 1024))

        assertEquals("“Huge.wsz” is too large", pick(vm, big.inputStream(), "Huge.wsz"))
        assertEquals("nothing is stored", before, library.list())
    }

    @Test
    fun `a zip past the most a pick reads says it is too large`() {
        val vm = started()

        assertEquals("“Endless.wsz” is too large", pick(vm, stream(SkinOps.MAX_PICK_BYTES + 1024, zipHead), "Endless.wsz"))
    }

    @Test
    fun `a large file that is not a zip says nothing`() {
        val vm = started()
        val before = library.list()

        pickSilently(vm, stream(SkinOps.MAX_SKIN_BYTES + 1024L), "clip.mp4")
        pickSilently(vm, stream(SkinOps.MAX_PICK_BYTES + 1024), "film.mkv")
        assertEquals("nothing is stored", before, library.list())
    }

    @Test
    fun `a modern skin whose zip directory is cut off is still told apart`() {
        val vm = started()
        val whole = zip("skin.xml" to skinXml, "player.xml" to skinXml)
        // the end of a zip is its directory: without it only the entries themselves remain
        val cut = whole.copyOf(whole.size - 30)

        assertEquals("“Cut.wal” is a modern skin", pick(vm, cut.inputStream(), "Cut.wal"))
    }

    @Test
    fun `a notice waits for the prompt on screen to be answered`() {
        val vm = started()
        val first = AmpPrompt("First", "", "OK", null) { vm.state.prompt = null }
        vm.state.prompt = first

        vm.skinOps.load(zip("skin.xml" to skinXml).inputStream(), "Bento.wal")
        await { vm.state.waitingNotice != null }
        assertSame("the prompt on screen stays", first, vm.state.prompt)

        first.onConfirm()
        await { vm.state.prompt != null }
        assertEquals("“Bento.wal” is a modern skin", vm.state.prompt!!.title)
        assertNull(vm.state.waitingNotice)
    }

    @Test
    fun `a caller that takes the result words it, so no notice is set`() {
        val vm = started()
        var result: String? = "unset"

        val load = vm.skinOps.load(zip("skin.xml" to skinXml).inputStream(), "Bento.wal") { result = it }
        await { load.isCompleted }

        assertNull("a modern skin reports no id", result)
        assertNull("the caller words it", vm.state.prompt)
        assertNull(vm.state.waitingNotice)
    }
}
