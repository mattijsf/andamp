// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import android.app.Application
import androidx.compose.ui.graphics.Color
import androidx.test.core.app.ApplicationProvider
import nl.mattix.andamp.skin.BundledSkins
import nl.mattix.andamp.skin.FakePalette
import nl.mattix.andamp.skin.LiveSkins
import nl.mattix.andamp.skin.SkinDist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * [CurrentSkin]'s fallback order: a bundled entry is built from its template while the
 * phone has a palette and from its file otherwise, and the cache key follows the palette
 * as well as the id.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class CurrentSkinTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val library = SkinLibrary(app)
    private val dark = SkinDist.baselineScheme("dark")
    private val other = dark.copy(primary = Color(0xFF00E250), surface = Color(0xFF101410))

    @Before
    fun clean() {
        app
            .getSharedPreferences("skins", 0)
            .edit()
            .clear()
            .commit()
        app
            .getSharedPreferences("palette", 0)
            .edit()
            .clear()
            .commit()
    }

    /** A preference that says "base" loads the bundled base skin, AndAmp Dark. */
    @Test
    fun `a preference of base loads AndAmp Dark`() {
        library.currentId = SkinEntry.BASE_ID

        val skin = CurrentSkin.load(app, library, LiveSkins { _, _ -> null })

        assertNotNull(skin)
        assertEquals("AndAmp Dark", skin!!.name)
    }

    /** A preference naming a skin that is neither bundled nor stored falls through to the base. */
    @Test
    fun `an unknown skin id falls back to the base`() {
        library.currentId = "a-skin-from-a-build-that-no-longer-exists"

        val skin = CurrentSkin.load(app, library, LiveSkins { _, _ -> null })

        assertNotNull(skin)
        assertEquals("AndAmp Dark", skin!!.name)
    }

    @Test
    fun `a live entry is built from its template`() {
        val live = LiveSkins(FakePalette(dark))

        val skin = CurrentSkin.stored(app, library, BundledSkins.LIGHT.id, fallback = null, live = live)

        assertNotNull(skin)
        assertSame(dark, skin!!.liveScheme)
        assertEquals("AndAmp Light", skin.name)
    }

    @Test
    fun `without a palette the entry falls back to the shipped file`() {
        val live = LiveSkins(FakePalette(null))

        val skin = CurrentSkin.stored(app, library, BundledSkins.LIGHT.id, fallback = null, live = live)

        assertNotNull(skin)
        assertNull("a skin built from its file has no live scheme", skin!!.liveScheme)
        assertEquals("AndAmp Light", skin.name)
    }

    @Test
    fun `the base is live when the phone allows and the file when it does not`() {
        assertSame(dark, CurrentSkin.base(app, LiveSkins(FakePalette(dark))).liveScheme)
        assertNull(CurrentSkin.base(app, LiveSkins(FakePalette(null))).liveScheme)
    }

    @Test
    fun `the widget's memo follows the palette`() {
        val palette = FakePalette(dark)
        val live = LiveSkins(palette)
        val first = CurrentSkin.cached(app, library, live)

        assertSame("the same palette gives the same skin", first, CurrentSkin.cached(app, library, live))
        palette.scheme = other
        val moved = CurrentSkin.cached(app, library, live)
        assertNotSame("a new palette gives a new skin", first, moved)
        assertSame(other, moved!!.liveScheme)
    }

    /** A user skin borrows from the base, so the base's palette is part of its key. */
    @Test
    fun `a worn user skin's key changes with the base's palette`() {
        val mine = library.save(app.assets.open("skins/AndAmp Spot.wsz").use { it.readBytes() }, "Mine.wsz")
        library.currentId = mine.id
        val palette = FakePalette(dark)
        val live = LiveSkins(palette)
        val before = CurrentSkin.cacheKey(app, library, live)

        palette.scheme = other

        assertNotEquals(before, CurrentSkin.cacheKey(app, library, live))
        assertEquals("Mine.wsz", CurrentSkin.cached(app, library, live)!!.name)
    }

    /** The parsed skin is cached under this key, so the key changes when a source's skin goes on. */
    @Test
    fun `the cache key changes when a source's skin goes on`() {
        val live = LiveSkins(FakePalette(null))
        library.currentId = BundledSkins.LIGHT.id
        val own = CurrentSkin.cacheKey(app, library, live)
        assertEquals(BundledSkins.LIGHT.name, CurrentSkin.cached(app, library, live)!!.name)

        library.wearing = BundledSkins.SPOT.id

        assertNotEquals("the key changes when a source's skin goes on", own, CurrentSkin.cacheKey(app, library, live))
        assertEquals(BundledSkins.SPOT.name, CurrentSkin.cached(app, library, live)!!.name)
    }

    @Test
    fun `taking a source's skin off puts the listener's own back`() {
        val live = LiveSkins(FakePalette(null))
        library.currentId = BundledSkins.LIGHT.id
        library.wearing = BundledSkins.SPOT.id
        assertEquals(BundledSkins.SPOT.name, CurrentSkin.cached(app, library, live)!!.name)

        library.wearing = null

        assertEquals(BundledSkins.LIGHT.name, CurrentSkin.cached(app, library, live)!!.name)
    }

    @Test
    fun `without a palette the key carries the id alone`() {
        val live = LiveSkins(FakePalette(null))

        assertEquals("base|null|null", CurrentSkin.cacheKey(app, library, live))
    }

    /** [CurrentSkin.live] is gated by the listener's switch in [PaletteStore]. */
    @Test
    fun `switching the wallpaper off makes the live skins wear their files`() {
        assertNotNull("the phone has a palette on this API", CurrentSkin.live.stamp(app, BundledSkins.BASE))

        PaletteStore(app).enabled = false

        assertNull(CurrentSkin.live.stamp(app, BundledSkins.BASE))
        assertNull(CurrentSkin.base(app).liveScheme)
    }
}
