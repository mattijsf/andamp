// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.theme

import android.app.Application
import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.toArgb
import androidx.test.core.app.ApplicationProvider
import nl.mattix.andamp.skin.PleditStyle
import nl.mattix.andamp.skin.Sheet
import nl.mattix.andamp.skin.Skin
import nl.mattix.andamp.skin.SkinLoader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs
import kotlin.math.pow

/**
 * The modern chrome takes its colors from the skin on screen: the seed comes
 * from the art, and every pair Material writes text on stays readable.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE) // the skin decodes through BitmapFactory
@Config(sdk = [35])
class SkinColorsTest {
    private lateinit var base: Skin

    @Before
    fun setUp() {
        base = SkinLoader.loadBase(ApplicationProvider.getApplicationContext<Application>())
    }

    /** A skin whose main window is one flat color, for a predictable seed. */
    private fun skinPainted(color: Color): Skin {
        val art = ImageBitmap(WIDTH, HEIGHT)
        Canvas(art).drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), Paint().apply { this.color = color })
        return Skin(
            sheets = mapOf(Sheet.MAIN to art),
            balanceUsesVolume = false,
            pledit = PleditStyle.DEFAULT,
            visColors = List(VIS_COLORS) { Color.Green },
            name = "painted",
        )
    }

    private fun hueOf(color: Color): Float {
        val max = maxOf(color.red, color.green, color.blue)
        val min = minOf(color.red, color.green, color.blue)
        if (max == min) return 0f
        val d = max - min
        val hue =
            when (max) {
                color.red -> (color.green - color.blue) / d
                color.green -> HUE_GREEN_TURN + (color.blue - color.red) / d
                else -> HUE_BLUE_TURN + (color.red - color.green) / d
            } * HUE_SEGMENT
        return (hue + FULL_CIRCLE) % FULL_CIRCLE
    }

    private fun luminance(color: Color): Double {
        fun channel(v: Float): Double {
            val c = v.toDouble()
            return if (c <= SRGB_KNEE) c / SRGB_SLOPE else ((c + SRGB_A) / (1 + SRGB_A)).pow(SRGB_GAMMA)
        }
        return LUMA_R * channel(color.red) + LUMA_G * channel(color.green) + LUMA_B * channel(color.blue)
    }

    private fun contrast(
        a: Color,
        b: Color,
    ): Double {
        val light = maxOf(luminance(a), luminance(b))
        val dark = minOf(luminance(a), luminance(b))
        return (light + CONTRAST_OFFSET) / (dark + CONTRAST_OFFSET)
    }

    private fun pairs(scheme: ColorScheme) =
        listOf(
            "primary" to (scheme.onPrimary to scheme.primary),
            "primaryContainer" to (scheme.onPrimaryContainer to scheme.primaryContainer),
            "secondary" to (scheme.onSecondary to scheme.secondary),
            "secondaryContainer" to (scheme.onSecondaryContainer to scheme.secondaryContainer),
            "tertiary" to (scheme.onTertiary to scheme.tertiary),
            "tertiaryContainer" to (scheme.onTertiaryContainer to scheme.tertiaryContainer),
            "surface" to (scheme.onSurface to scheme.surface),
            "surfaceVariant" to (scheme.onSurfaceVariant to scheme.surfaceVariant),
            "background" to (scheme.onBackground to scheme.background),
            "error" to (scheme.onError to scheme.error),
            "errorContainer" to (scheme.onErrorContainer to scheme.errorContainer),
        )

    @Test
    fun `the seed follows the hue of the art`() {
        val red = SkinColors.seedOf(skinPainted(Color(RED_SKIN)))!!
        val green = SkinColors.seedOf(skinPainted(Color(GREEN_SKIN)))!!

        assertNotEquals(red.toArgb(), green.toArgb())
        assertTrue(
            "a red skin seeds a red hue: ${hueOf(red)}deg",
            hueOf(red) < HUE_SLACK || hueOf(red) > FULL_CIRCLE - HUE_SLACK,
        )
        assertTrue("a green skin seeds a green hue: ${hueOf(green)}deg", abs(hueOf(green) - GREEN_HUE) < HUE_SLACK)
    }

    @Test
    fun `the bundled skin seeds a color, not a gray`() {
        val seed = SkinColors.seedOf(base)!!
        val spread = maxOf(seed.red, seed.green, seed.blue) - minOf(seed.red, seed.green, seed.blue)

        assertTrue("the base skin seeds a color: $seed", spread > MIN_SPREAD)
    }

    @Test
    fun `a skin with no art at all still yields a readable scheme`() {
        val bare =
            Skin(
                sheets = emptyMap(),
                balanceUsesVolume = false,
                pledit = PleditStyle.DEFAULT,
                visColors = List(VIS_COLORS) { Color.Green },
                name = "bare",
            )

        val scheme = SkinColors.schemeOf(bare)

        pairs(scheme).forEach { (role, pair) ->
            assertTrue("$role is readable in the fallback scheme", contrast(pair.first, pair.second) >= READABLE)
        }
    }

    @Test
    fun `every pair Material writes text on stays readable`() {
        listOf(base, skinPainted(Color(RED_SKIN)), skinPainted(Color(NAVY_SKIN))).forEach { skin ->
            val scheme = SkinColors.schemeOf(skin)
            pairs(scheme).forEach { (role, pair) ->
                val ratio = contrast(pair.first, pair.second)
                assertTrue("${skin.name}: $role is readable at $ratio to 1", ratio >= READABLE)
            }
        }
    }

    @Test
    fun `a dark scheme keeps its surfaces dark, whatever the skin`() {
        val scheme = SkinColors.schemeOf(skinPainted(Color(PALE_SKIN)))

        assertTrue("a pale skin keeps a dark surface", luminance(scheme.surface) < DARK_SURFACE)
    }

    @Test
    fun `the same skin always produces the same scheme`() {
        val once = SkinColors.schemeOf(base)
        val twice = SkinColors.schemeOf(base)

        assertEquals(once.primary, twice.primary)
        assertEquals(once.surfaceContainerHigh, twice.surfaceContainerHigh)
    }

    @Test
    fun `building a scheme stays within the time budget`() {
        val started = System.nanoTime()
        SkinColors.schemeOf(base)
        val ms = (System.nanoTime() - started) / NANOS_PER_MS

        // a generous bound: it must not take seconds
        assertTrue("building the scheme takes under $BUDGET_MS ms: ${ms}ms", ms < BUDGET_MS)
    }

    @Test
    fun `every style stays readable`() {
        val skin = skinPainted(Color(RED_SKIN))

        SkinSchemeStyle.entries.forEach { style ->
            val scheme = SkinColors.schemeOf(skin, style = style)
            pairs(scheme).forEach { (role, pair) ->
                assertTrue(
                    "$style: $role is readable at ${contrast(pair.first, pair.second)} to 1",
                    contrast(pair.first, pair.second) >= READABLE,
                )
            }
        }
    }

    @Test
    fun `fidelity stays nearer the skin's own hue than expressive does`() {
        // expressive shifts the supporting hues; fidelity keeps them
        val skin = skinPainted(Color(RED_SKIN))
        val seedHue = hueOf(SkinColors.seedOf(skin)!!)

        val fidelity = hueOf(SkinColors.schemeOf(skin, style = SkinSchemeStyle.FIDELITY).primary)
        val expressive = hueOf(SkinColors.schemeOf(skin, style = SkinSchemeStyle.EXPRESSIVE).primary)

        fun away(hue: Float) = minOf(abs(hue - seedHue), FULL_CIRCLE - abs(hue - seedHue))
        assertTrue(
            "fidelity stays nearer the seed hue: ${away(fidelity)}deg against expressive ${away(expressive)}deg",
            away(fidelity) <= away(expressive),
        )
    }

    @Test
    fun `the surfaces carry the skin's hue`() {
        // a menu is surfaces, so a change of skin has to show there
        val red = SkinColors.schemeOf(skinPainted(Color(RED_SKIN))).surface
        val green = SkinColors.schemeOf(skinPainted(Color(GREEN_SKIN))).surface

        fun spread(c: Color) = maxOf(c.red, c.green, c.blue) - minOf(c.red, c.green, c.blue)
        assertTrue("a red skin's surface carries color: $red", spread(red) > SURFACE_SPREAD)
        assertTrue("a green skin's surface carries color: $green", spread(green) > SURFACE_SPREAD)
        assertTrue("two skins produce different surfaces", red != green)
        assertTrue("the red skin's surface leans red", red.red > red.blue)
        assertTrue("the green skin's surface leans green", green.green > green.red)
    }

    @Test
    fun `turning the tint off changes the surface`() {
        val plain = SkinColors.schemeOf(skinPainted(Color(RED_SKIN)), surfaceTint = 0f).surface
        val tinted = SkinColors.schemeOf(skinPainted(Color(RED_SKIN))).surface

        assertTrue("turning the tint off changes the surface", plain != tinted)
    }

    @Test
    fun `the accent keeps the skin's own hue`() {
        // Material's expressive style on its own turns the primary hue 240
        // degrees off the seed
        val skin = skinPainted(Color(BLUE_SKIN))
        val seedHue = hueOf(SkinColors.seedOf(skin)!!)

        val primaryHue = hueOf(SkinColors.schemeOf(skin).primary)

        val away = minOf(abs(primaryHue - seedHue), FULL_CIRCLE - abs(primaryHue - seedHue))
        assertTrue("the accent stays near the skin's hue: ${away}deg away", away < ACCENT_SLACK)
    }

    @Test
    fun `a skin that is gray with one accent is read by its accent`() {
        // a monochrome skin: black window, a small blue panel. The scorer
        // discards the grays
        val art = ImageBitmap(WIDTH, HEIGHT)
        val canvas = Canvas(art)
        canvas.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), Paint().apply { color = Color(0xFF141414) })
        canvas.drawRect(0f, 0f, ACCENT_W.toFloat(), ACCENT_H.toFloat(), Paint().apply { color = Color(BLUE_SKIN) })
        // gray everywhere it declares itself, so the panel is the only color
        val skin =
            Skin(
                sheets = mapOf(Sheet.MAIN to art),
                balanceUsesVolume = false,
                pledit =
                    PleditStyle.DEFAULT.copy(
                        normal = Color(0xFFB0B0B0),
                        current = Color(0xFFE0E0E0),
                        selectedBg = Color(0xFF2A2A2A),
                    ),
                visColors = List(VIS_COLORS) { Color(0xFF8A8A8A) },
                name = "monochrome",
            )

        val hue = hueOf(SkinColors.seedOf(skin)!!)

        assertTrue("a blue-accented skin seeds a blue hue: ${hue}deg", abs(hue - BLUE_HUE) < HUE_SLACK)
    }

    /** Flat art, with whatever the skin declares about itself. */
    private fun flatSkin(
        vis: Color,
        text: Color = Color(0xFF9A9A9A),
    ): Skin {
        val art = ImageBitmap(WIDTH, HEIGHT)
        Canvas(art).drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), Paint().apply { color = Color(0xFF1A1A1C) })
        return Skin(
            sheets = mapOf(Sheet.MAIN to art),
            balanceUsesVolume = false,
            pledit = PleditStyle.DEFAULT.copy(normal = text, current = text, selectedBg = Color(0xFF2A2A2C)),
            visColors = List(VIS_COLORS) { vis },
            name = "flat",
        )
    }

    @Test
    fun `a flat skin is read by what it declares about itself`() {
        // black chrome with one amber accent is a real family of skins; the
        // accent lives in VISCOLOR even when it is a few pixels on screen
        val amber = flatSkin(vis = Color(0xFFFFB300))

        val hue = hueOf(SkinColors.seedOf(amber)!!)

        assertTrue("an amber skin seeds an amber hue: ${hue}deg", abs(hue - AMBER_HUE) < HUE_SLACK)
    }

    @Test
    fun `a skin with no color anywhere gets gray chrome`() {
        // handed nothing colorful, Material's scorer returns a default blue of
        // its own, so a gray skin has no seed at all
        val grey = flatSkin(vis = Color(0xFF8A8A8A), text = Color(0xFFB0B0B0))

        assertEquals(null, SkinColors.seedOf(grey))
        val scheme = SkinColors.schemeOf(grey)

        fun spread(c: Color) = maxOf(c.red, c.green, c.blue) - minOf(c.red, c.green, c.blue)
        assertTrue("a gray skin gets a gray surface: ${scheme.surface}", spread(scheme.surface) < GREY_SPREAD)
        assertTrue("a gray skin gets a gray accent: ${scheme.primary}", spread(scheme.primary) < GREY_SPREAD)
    }

    @Test
    fun `art with color in it beats what the skin declares`() {
        val art = ImageBitmap(WIDTH, HEIGHT)
        Canvas(art).drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), Paint().apply { color = Color(BLUE_SKIN) })
        val skin =
            Skin(
                sheets = mapOf(Sheet.MAIN to art),
                balanceUsesVolume = false,
                pledit = PleditStyle.DEFAULT,
                visColors = List(VIS_COLORS) { Color(0xFFFFB300) },
                name = "blue art, amber analyzer",
            )

        val hue = hueOf(SkinColors.seedOf(skin)!!)

        assertTrue("blue art wins over the declared amber: ${hue}deg", abs(hue - BLUE_HUE) < HUE_SLACK)
    }

    @Test
    fun `an accent-only skin keeps a darker surface than a colorful one`() {
        // a black skin with one amber button keeps its accent on the buttons
        // and off the surface
        val art = ImageBitmap(WIDTH, HEIGHT)
        val canvas = Canvas(art)
        canvas.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), Paint().apply { color = Color(0xFF141414) })
        canvas.drawRect(0f, 0f, ACCENT_W.toFloat(), ACCENT_H.toFloat(), Paint().apply { color = Color(AMBER_SKIN) })
        val accentOnly =
            Skin(
                sheets = mapOf(Sheet.MAIN to art),
                balanceUsesVolume = false,
                pledit = PleditStyle.DEFAULT.copy(normal = Color(0xFFB0B0B0), current = Color(0xFFE0E0E0)),
                visColors = List(VIS_COLORS) { Color(0xFF8A8A8A) },
                name = "accent only",
            )
        val allAmber = skinPainted(Color(AMBER_SKIN))

        val sparse = SkinColors.schemeOf(accentOnly)
        val soaked = SkinColors.schemeOf(allAmber)

        assertTrue("the accent reaches the buttons", hueOf(sparse.primary) - AMBER_HUE < HUE_SLACK)
        assertTrue(
            "a one-button accent keeps the surface darker: ${sparse.surface} against ${soaked.surface}",
            luminance(sparse.surface) < luminance(soaked.surface),
        )
    }

    private companion object {
        const val WIDTH = 275
        const val HEIGHT = 116
        const val VIS_COLORS = 24
        const val RED_SKIN = 0xFFB3261EL
        const val GREEN_SKIN = 0xFF1E7B3CL
        const val NAVY_SKIN = 0xFF102A43L
        const val BLUE_SKIN = 0xFF1E90FFL
        const val BLUE_HUE = 210f
        const val AMBER_HUE = 42f
        const val AMBER_SKIN = 0xFFFFB300L
        const val GREY_SPREAD = 0.04f
        const val ACCENT_SLACK = 30f
        const val ACCENT_W = 24
        const val ACCENT_H = 12
        const val PALE_SKIN = 0xFFFFE08AL
        const val GREEN_HUE = 140f
        const val HUE_SLACK = 45f
        const val HUE_SEGMENT = 60f
        const val HUE_GREEN_TURN = 2f
        const val HUE_BLUE_TURN = 4f
        const val FULL_CIRCLE = 360f
        const val MIN_SPREAD = 0.08f
        const val READABLE = 4.5
        const val DARK_SURFACE = 0.1
        const val SURFACE_SPREAD = 0.05f
        const val BUDGET_MS = 1_500
        const val NANOS_PER_MS = 1_000_000
        const val SRGB_KNEE = 0.03928
        const val SRGB_SLOPE = 12.92
        const val SRGB_A = 0.055
        const val SRGB_GAMMA = 2.4
        const val LUMA_R = 0.2126
        const val LUMA_G = 0.7152
        const val LUMA_B = 0.0722
        const val CONTRAST_OFFSET = 0.05
    }
}
