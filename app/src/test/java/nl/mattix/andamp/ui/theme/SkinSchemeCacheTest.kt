// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.theme

import android.app.Application
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.key
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import nl.mattix.andamp.skin.PleditStyle
import nl.mattix.andamp.skin.Sheet
import nl.mattix.andamp.skin.Skin
import nl.mattix.andamp.skin.SkinLoader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Growing a scheme from a skin's art costs milliseconds on the main thread, so
 * it happens once per skin, not once per composition.
 *
 * Counted, not timed: how long a quantization takes depends on the machine.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class SkinSchemeCacheTest {
    @get:Rule
    val rule = createComposeRule()

    private var built = 0

    /** Stands in for the real quantization, and keeps the tally. */
    private val counting: (Skin, Boolean, SkinSchemeStyle) -> ColorScheme = { _, _, _ ->
        built++
        darkColorScheme()
    }

    private fun skin(name: String) =
        Skin(
            sheets = mapOf(Sheet.MAIN to ImageBitmap(WIDTH, HEIGHT)),
            balanceUsesVolume = false,
            pledit = PleditStyle.DEFAULT,
            visColors = emptyList(),
            name = name,
        )

    @Before
    fun setUp() {
        SkinSchemes.forget()
        built = 0
    }

    /**
     * A live skin was made from a scheme and wears that one.
     */
    @Test
    fun `a live skin is not quantized`() {
        val scheme =
            darkColorScheme(
                primary =
                    androidx.compose.ui.graphics
                        .Color(0xFF00E250),
            )
        val live =
            Skin(
                sheets = mapOf(Sheet.MAIN to ImageBitmap(WIDTH, HEIGHT)),
                balanceUsesVolume = false,
                pledit = PleditStyle.DEFAULT,
                visColors = emptyList(),
                name = "live",
                liveScheme = scheme,
            )
        var worn: ColorScheme? = null

        rule.setContent { worn = rememberSkinColorScheme(live) }
        rule.waitForIdle()

        assertSame(scheme, worn)
    }

    @Test
    fun `a skin's scheme is grown once, however many surfaces ask for it`() {
        val skin = skin("one")

        repeat(4) { SkinSchemes.of(skin, build = counting) }

        assertEquals(1, built)
    }

    @Test
    fun `every surface is handed the same scheme`() {
        val skin = skin("one")

        val first = SkinSchemes.of(skin, build = counting)
        val second = SkinSchemes.of(skin, build = counting)

        assertSame(first, second)
    }

    @Test
    fun `another skin gets a scheme of its own`() {
        SkinSchemes.of(skin("one"), build = counting)
        SkinSchemes.of(skin("two"), build = counting)

        assertEquals(2, built)
    }

    @Test
    fun `light and dark are built separately`() {
        val skin = skin("one")

        SkinSchemes.of(skin, dark = true, build = counting)
        SkinSchemes.of(skin, dark = false, build = counting)

        assertEquals(2, built)
    }

    @Test
    fun `each style is built separately`() {
        val skin = skin("one")

        SkinSchemes.of(skin, style = SkinSchemeStyle.VIBRANT, build = counting)
        SkinSchemes.of(skin, style = SkinSchemeStyle.EXPRESSIVE, build = counting)

        assertEquals(2, built)
    }

    /**
     * Separate call sites hold separate `remember` slots, so the cache has to
     * outlive them.
     */
    @Test
    fun `two call sites in a composition share one scheme`() {
        val base = SkinLoader.loadBase(ApplicationProvider.getApplicationContext<Application>())
        lateinit var player: ColorScheme
        lateinit var floating: ColorScheme

        rule.setContent {
            player = rememberSkinColorScheme(base)
            key("another surface") { floating = rememberSkinColorScheme(base) }
        }

        assertSame(player, floating)
    }

    private companion object {
        const val WIDTH = 275
        const val HEIGHT = 116
    }
}
