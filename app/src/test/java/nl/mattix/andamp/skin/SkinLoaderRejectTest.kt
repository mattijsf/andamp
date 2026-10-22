// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.skin

import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SkinLoaderRejectTest {
    @Test
    fun `garbage bytes are rejected`() {
        // guards against a non-zip stream, which yields zero members, loading
        // as a clone of the fallback skin
        assertThrows(IllegalArgumentException::class.java) {
            SkinLoader.load(ByteArray(64) { it.toByte() }.inputStream(), fallback = null, name = "garbage")
        }
    }

    @Test
    fun `an empty zip is rejected`() {
        val emptyZip =
            ByteArrayOutputStream().also { ZipOutputStream(it).close() }.toByteArray()
        assertThrows(IllegalArgumentException::class.java) {
            SkinLoader.load(emptyZip.inputStream(), fallback = null, name = "empty")
        }
    }

    @Test
    fun `a zip with unrelated members is rejected`() {
        val zip =
            ByteArrayOutputStream()
                .also { bos ->
                    ZipOutputStream(bos).use { zos ->
                        zos.putNextEntry(ZipEntry("readme.txt"))
                        zos.write("not a skin".toByteArray())
                        zos.closeEntry()
                    }
                }.toByteArray()
        // members exist but none are skin sheets; with no fallback the loader
        // fails on the first required sheet
        assertThrows(IllegalStateException::class.java) {
            SkinLoader.load(zip.inputStream(), fallback = null, name = "readme-only")
        }
    }

    @Test
    fun `a zip bomb is refused before it fills memory`() {
        // 64 MB of zeros as main.bmp, a few kB packed: the unpacked size is what counts
        val bomb =
            ByteArrayOutputStream()
                .also { bos ->
                    ZipOutputStream(bos).use { zos ->
                        zos.putNextEntry(ZipEntry("main.bmp"))
                        val zeros = ByteArray(1024 * 1024)
                        repeat(64) { zos.write(zeros) }
                        zos.closeEntry()
                    }
                }.toByteArray()
        assertThrows(IOException::class.java) {
            SkinLoader.load(bomb.inputStream(), fallback = null, name = "bomb")
        }
    }

    @Test
    fun `an archive with too many members is refused`() {
        val many =
            ByteArrayOutputStream()
                .also { bos ->
                    ZipOutputStream(bos).use { zos ->
                        repeat(SkinLoader.MAX_MEMBERS + 1) { i ->
                            zos.putNextEntry(ZipEntry("m$i.txt"))
                            zos.closeEntry()
                        }
                    }
                }.toByteArray()
        assertThrows(IOException::class.java) {
            SkinLoader.load(many.inputStream(), fallback = null, name = "many")
        }
    }
}
