// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.skin

import android.app.Application
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * How large a sheet a skin may bring.
 *
 * A sheet is decoded at the size its file states, so the size is checked
 * before any pixel is decoded. A sheet over the limit is treated like a
 * corrupt one: the base skin's takes its place.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE) // the sheets are encoded and decoded for real
@Config(sdk = [35])
class SkinSheetSizeTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    private val base by lazy { SkinLoader.loadBase(app) }

    /** A skin whose only member is a main sheet of [width] by [height] pixels. */
    private fun skinWithMain(
        width: Int,
        height: Int,
    ): Skin {
        val sheet = ByteArrayOutputStream()
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, sheet)
        val archive = ByteArrayOutputStream()
        ZipOutputStream(archive).use { zip ->
            zip.putNextEntry(ZipEntry("main.png"))
            zip.write(sheet.toByteArray())
            zip.closeEntry()
        }
        return SkinLoader.load(archive.toByteArray().inputStream(), fallback = base, name = "sized")
    }

    @Test
    fun `a sheet of exactly the limit is the skin's own`() {
        val skin = skinWithMain(SIDE, SIDE)
        assertEquals(SIDE, skin[Sheet.MAIN].width)
        assertEquals(SIDE, skin[Sheet.MAIN].height)
    }

    @Test
    fun `a long thin sheet within the limit is the skin's own`() {
        assertEquals(4 * SIDE, skinWithMain(4 * SIDE, SIDE / 4)[Sheet.MAIN].width)
    }

    @Test
    fun `a sheet over the limit gives way to the base skin's`() {
        assertSame(base[Sheet.MAIN], skinWithMain(SIDE + 1, SIDE)[Sheet.MAIN])
    }

    private companion object {
        /** The side of a square sheet that holds exactly [SkinLoader.MAX_SHEET_PIXELS]. */
        const val SIDE = 1024
    }
}
