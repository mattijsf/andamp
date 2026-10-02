// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.skin

import android.content.Context
import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A live skin is built once per palette and handed out again while the palette
 * is unchanged.
 *
 * The same object is returned, not an equal one, because the scheme cache and
 * the widget's memo are keyed on the [Skin] object.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class LiveSkinsTest {
    private val app = ApplicationProvider.getApplicationContext<android.app.Application>()

    /** A palette that can be changed between asks, the way a wallpaper is. */
    private class FakePalette(
        var scheme: ColorScheme?,
    ) : (Context, Boolean) -> ColorScheme? {
        override fun invoke(
            context: Context,
            dark: Boolean,
        ) = scheme
    }

    private val dark = SkinDist.baselineScheme("dark")

    private fun pixels(skin: Skin): IntArray {
        val bitmap = skin[Sheet.MAIN].asAndroidBitmap()
        return IntArray(
            bitmap.width * bitmap.height,
        ).also { bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height) }
    }

    @Test
    fun `the same palette is the same skin`() {
        val live = LiveSkins(FakePalette(dark))

        val first = live.build(app, BundledSkins.BASE)
        val again = live.build(app, BundledSkins.BASE)

        assertSame(first, again)
    }

    @Test
    fun `a changed palette is a new skin`() {
        val palette = FakePalette(dark)
        val live = LiveSkins(palette)
        val before = live.build(app, BundledSkins.BASE)!!

        palette.scheme = dark.copy(primary = Color(0xFF00E250), surface = Color(0xFF101410))
        val after = live.build(app, BundledSkins.BASE)!!

        assertNotSame(before, after)
        assertFalse("the art changes with the palette", pixels(before).contentEquals(pixels(after)))
    }

    @Test
    fun `a palette that comes back is the same art again`() {
        val palette = FakePalette(dark)
        val live = LiveSkins(palette)
        val first = pixels(live.build(app, BundledSkins.BASE)!!)
        palette.scheme = dark.copy(primary = Color(0xFF00E250))
        live.build(app, BundledSkins.BASE)

        palette.scheme = dark

        assertArrayEquals(first, pixels(live.build(app, BundledSkins.BASE)!!))
    }

    @Test
    fun `spot is never live`() {
        assertNull(LiveSkins(FakePalette(dark)).build(app, BundledSkins.SPOT))
        assertNull(LiveSkins(FakePalette(dark)).stamp(app, BundledSkins.SPOT))
    }

    @Test
    fun `no palette means no live skin`() {
        val live = LiveSkins(FakePalette(null))

        assertNull(live.build(app, BundledSkins.BASE))
        assertNull(live.stamp(app, BundledSkins.BASE))
    }

    @Test
    fun `a live skin carries the scheme it was bound to`() {
        assertSame(dark, LiveSkins(FakePalette(dark)).build(app, BundledSkins.BASE)!!.liveScheme)
    }

    /** The stamp is what the widget keys its picture by. */
    @Test
    fun `the stamp follows the palette`() {
        val palette = FakePalette(dark)
        val live = LiveSkins(palette)
        val before = live.stamp(app, BundledSkins.BASE)!!

        assertArrayEquals(before, live.stamp(app, BundledSkins.BASE))
        palette.scheme = dark.copy(primary = Color(0xFF00E250))
        assertFalse(before.contentEquals(live.stamp(app, BundledSkins.BASE)!!))
    }
}
