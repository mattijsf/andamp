// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.skin

import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
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
}
