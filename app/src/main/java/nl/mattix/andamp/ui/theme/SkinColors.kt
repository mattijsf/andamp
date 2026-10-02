// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import com.google.android.material.color.MaterialColors
import com.google.android.material.color.utilities.DynamicColor
import com.google.android.material.color.utilities.DynamicScheme
import com.google.android.material.color.utilities.Hct
import com.google.android.material.color.utilities.MaterialDynamicColors
import com.google.android.material.color.utilities.QuantizerCelebi
import com.google.android.material.color.utilities.SchemeExpressive
import com.google.android.material.color.utilities.SchemeFidelity
import com.google.android.material.color.utilities.SchemeMonochrome
import com.google.android.material.color.utilities.SchemeTonalSpot
import com.google.android.material.color.utilities.SchemeVibrant
import com.google.android.material.color.utilities.Score
import nl.mattix.andamp.skin.Sheet
import nl.mattix.andamp.skin.Skin

/**
 * Which of Material's color styles turns the skin's seed color into a scheme.
 *
 * [EXPRESSIVE] is Material's color style of that name, which turns the primary hue 240 degrees off
 * the seed: the blue accents of a monochrome skin come out yellow-green. The default, [VIBRANT],
 * keeps the skin's hue.
 */
enum class SkinSchemeStyle {
    /** Keeps the skin's hue at high chroma. The default. */
    VIBRANT,

    /** The same hue, held down to Material's usual chroma. */
    TONAL_SPOT,

    /** Closest to the art's own color, chroma included. */
    FIDELITY,

    /** Material's expressive color style, with the primary hue turned away from the seed. */
    EXPRESSIVE,
}

/**
 * Colors the Material chrome after the loaded skin.
 *
 * Menus, dialogs and the preferences screen are Material's; they are not drawn in skin art (see
 * ENGINEERING.md). The skin's art gives one seed color, and Material's color engine turns it into a
 * full scheme.
 *
 * [QuantizerCelebi], [Score] and the scheme classes come from the Material components library,
 * where they are marked for the library group's own use, which is why the call sites carry a
 * suppression.
 */
object SkinColors {
    /**
     * The skin's seed color: Material's scoring over the pixels of the main window's sheet. The
     * scorer discards the grays and near-blacks a window frame is mostly made of.
     */
    @Suppress("RestrictedApi") // Material's color engine; see the class comment
    fun seedOf(skin: Skin): Color? {
        val art = skin.getOrNull(Sheet.MAIN) ?: return declaredAccent(skin)
        val counts = QuantizerCelebi.quantize(argbPixels(art), MAX_COLORS)
        // The sentinel is needed: handed nothing colorful, Score returns a constant of its own
        // (Google's blue), and a flat gray skin would get a color that appears nowhere in it.
        val dominant = Score.score(counts, 1, NOTHING_FOUND, true).firstOrNull() ?: NOTHING_FOUND
        if (dominant != NOTHING_FOUND) return Color(dominant)
        // The quantizer answers "what is this image mostly", and a black skin with an amber button
        // is mostly black. So the accent is counted directly: of the pixels that carry any color,
        // the commonest.
        return accentInArt(art)?.colour ?: declaredAccent(skin)
    }

    /**
     * The commonest color among the pixels that are not gray. Winamp skins are mostly chrome with
     * an accent that is a handful of pixels: a lit button, an EQ readout, a strip on a slider.
     */
    private fun accentInArt(art: ImageBitmap): Accent? {
        val map = art.toPixelMap()
        val counts = HashMap<Int, Int>()
        for (y in 0 until map.height) {
            for (x in 0 until map.width) {
                val pixel = map[x, y]
                val high = maxOf(pixel.red, pixel.green, pixel.blue)
                val low = minOf(pixel.red, pixel.green, pixel.blue)
                // gray, near-black and near-white pixels are skipped
                if (high - low < COLOURED || high < DARKEST || low > PALEST) continue
                val key = pixel.toArgb() and BUCKET
                counts[key] = (counts[key] ?: 0) + 1
            }
        }
        val best = counts.maxByOrNull { it.value }?.key ?: return null
        val colour = Color(best or OPAQUE)
        if (Hct.fromInt(colour.toArgb()).chroma < MEANINGFUL_CHROMA) return null
        val coloured = counts.values.sum().toFloat() / (map.width * map.height)
        return Accent(colour, coloured)
    }

    /** A skin's color, and the share of its art that carries any. */
    private data class Accent(
        val colour: Color,
        val coverage: Float,
    )

    /**
     * The colors a skin declares in text: the analyzer's gradient (VISCOLOR.TXT) and the playlist's
     * text colors (PLEDIT.TXT). A skin with flat gray art can still have an accent there. Null
     * means none of them is colorful enough.
     */
    @Suppress("RestrictedApi") // Hct, for measuring colorfulness
    private fun declaredAccent(skin: Skin): Color? {
        val declared = skin.visColors + listOf(skin.pledit.normal, skin.pledit.current, skin.pledit.selectedBg)
        return declared
            .map { it to Hct.fromInt(it.toArgb()) }
            // a color too dark or too pale to build a palette on is not an accent
            .filter { (_, hct) -> hct.tone in USABLE_TONES }
            .maxByOrNull { (_, hct) -> hct.chroma }
            ?.takeIf { (_, hct) -> hct.chroma >= MEANINGFUL_CHROMA }
            ?.first
    }

    /**
     * How much of the skin's color belongs on a surface. The tint follows the share of the art that
     * carries color, so a blue skin gives a blue sheet and a black skin with one amber button keeps
     * a near-neutral one, with a floor of [TINT_FLOOR].
     */
    private fun surfaceShare(skin: Skin): Float {
        val art = skin.getOrNull(Sheet.MAIN) ?: return TINT_FLOOR
        val coverage = accentInArt(art)?.coverage ?: return TINT_FLOOR
        return (coverage / FULLY_COLOURED).coerceIn(TINT_FLOOR, 1f)
    }

    /**
     * A Material scheme built from [skin]'s seed color. Dark by default, because the windows are.
     */
    @Suppress("RestrictedApi") // Material's color engine; see the class comment
    fun schemeOf(
        skin: Skin,
        dark: Boolean = true,
        style: SkinSchemeStyle = SkinSchemeStyle.VIBRANT,
        contrast: Double = 0.0,
        /**
         * How much of the skin's color is laid over the neutral surfaces a menu is made of.
         * Material keeps those near-gray; without this, changing skin would change only the
         * accents.
         */
        surfaceTint: Float = SURFACE_TINT,
    ): ColorScheme {
        val found = seedOf(skin)
        // A skin with no color anywhere gets gray chrome: a hue taken from a near-gray is rounding
        // noise.
        val plain = Hct.fromInt((found ?: Color.White).toArgb())
        val seed =
            if (found == null) {
                plain
            } else {
                // a muted seed mutes every accent downstream, so its chroma is raised to at least
                // MIN_SEED_CHROMA while the hue stays
                Hct.from(plain.hue, maxOf(plain.chroma, MIN_SEED_CHROMA), plain.tone)
            }
        val scheme =
            when {
                found == null -> SchemeMonochrome(seed, dark, contrast)
                else -> chromatic(style, seed, dark, contrast)
            }
        val strength = if (found == null) 0f else surfaceTint * surfaceShare(skin)
        return schemeFrom(scheme).tintedBy(Color(seed.toInt()), strength)
    }

    @Suppress("RestrictedApi") // Material's color engine; see the class comment
    private fun chromatic(
        style: SkinSchemeStyle,
        seed: Hct,
        dark: Boolean,
        contrast: Double,
    ) = when (style) {
        SkinSchemeStyle.EXPRESSIVE -> SchemeExpressive(seed, dark, contrast)
        SkinSchemeStyle.FIDELITY -> SchemeFidelity(seed, dark, contrast)
        SkinSchemeStyle.TONAL_SPOT -> SchemeTonalSpot(seed, dark, contrast)
        SkinSchemeStyle.VIBRANT -> SchemeVibrant(seed, dark, contrast)
    }

    /**
     * The surface family, laid over with [tint] of [skin]'s color. surfaceVariant is left alone:
     * its pair with onSurfaceVariant has a thin contrast margin, and a tint would reduce it.
     */
    private fun ColorScheme.tintedBy(
        skin: Color,
        tint: Float,
    ): ColorScheme {
        if (tint <= 0f) return this

        fun over(base: Color) = Color(MaterialColors.layer(base.toArgb(), skin.toArgb(), tint))
        return copy(
            background = over(background),
            surface = over(surface),
            surfaceDim = over(surfaceDim),
            surfaceBright = over(surfaceBright),
            surfaceContainer = over(surfaceContainer),
            surfaceContainerLow = over(surfaceContainerLow),
            surfaceContainerLowest = over(surfaceContainerLowest),
            surfaceContainerHigh = over(surfaceContainerHigh),
            surfaceContainerHighest = over(surfaceContainerHighest),
        )
    }

    /** Every Material role, read off [scheme]. */
    @Suppress("RestrictedApi", "LongMethod") // one line per Material role
    private fun schemeFrom(scheme: DynamicScheme): ColorScheme {
        val roles = MaterialDynamicColors()

        fun color(of: MaterialDynamicColors.() -> DynamicColor) = Color(roles.of().getArgb(scheme))
        return darkColorScheme(
            primary = color { primary() },
            onPrimary = color { onPrimary() },
            primaryContainer = color { primaryContainer() },
            onPrimaryContainer = color { onPrimaryContainer() },
            inversePrimary = color { inversePrimary() },
            secondary = color { secondary() },
            onSecondary = color { onSecondary() },
            secondaryContainer = color { secondaryContainer() },
            onSecondaryContainer = color { onSecondaryContainer() },
            tertiary = color { tertiary() },
            onTertiary = color { onTertiary() },
            tertiaryContainer = color { tertiaryContainer() },
            onTertiaryContainer = color { onTertiaryContainer() },
            background = color { background() },
            onBackground = color { onBackground() },
            surface = color { surface() },
            onSurface = color { onSurface() },
            surfaceVariant = color { surfaceVariant() },
            onSurfaceVariant = color { onSurfaceVariant() },
            surfaceTint = color { surfaceTint() },
            inverseSurface = color { inverseSurface() },
            inverseOnSurface = color { inverseOnSurface() },
            error = color { error() },
            onError = color { onError() },
            errorContainer = color { errorContainer() },
            onErrorContainer = color { onErrorContainer() },
            outline = color { outline() },
            outlineVariant = color { outlineVariant() },
            scrim = color { scrim() },
            surfaceBright = color { surfaceBright() },
            surfaceDim = color { surfaceDim() },
            surfaceContainer = color { surfaceContainer() },
            surfaceContainerHigh = color { surfaceContainerHigh() },
            surfaceContainerHighest = color { surfaceContainerHighest() },
            surfaceContainerLow = color { surfaceContainerLow() },
            surfaceContainerLowest = color { surfaceContainerLowest() },
        )
    }

    /** The sheet's pixels as ARGB ints, as the quantizer reads them. */
    private fun argbPixels(art: ImageBitmap): IntArray {
        val map = art.toPixelMap()
        val pixels = IntArray(map.width * map.height)
        var at = 0
        for (y in 0 until map.height) {
            for (x in 0 until map.width) {
                pixels[at++] = map[x, y].toArgb()
            }
        }
        return pixels
    }

    /** How many colors the quantizer reduces an image to before it is scored. */
    private const val MAX_COLORS = 128

    /** The lowest chroma a seed is given. */
    private const val MIN_SEED_CHROMA = 40.0

    /** Below this chroma a color counts as gray. */
    private const val MEANINGFUL_CHROMA = 12.0

    /** Too dark or too pale to build a palette on, whatever its hue. */
    private val USABLE_TONES = 12.0..92.0

    /** The spread between a pixel's highest and lowest channel before it counts as colored. */
    private const val COLOURED = 0.12f

    /** Ends of the range where a hue cannot be read off a pixel. */
    private const val DARKEST = 0.12f
    private const val PALEST = 0.95f

    /** Five bits a channel: near-identical pixels count as one color. */
    private const val BUCKET = 0x00F8F8F8
    private const val OPAQUE = 0xFF000000.toInt()

    /** The fallback handed to Score; getting it back means the art had no colorful pixels. */
    private const val NOTHING_FOUND = 0x00000001

    /** The default surface tint. */
    private const val SURFACE_TINT = 0.22f

    /** The colored share of the art at which surfaces are tinted at full strength. */
    private const val FULLY_COLOURED = 0.35f

    /** The least share of the tint any colored skin gets. */
    private const val TINT_FLOOR = 0.18f
}

/**
 * The scheme for [skin]. `remember` keeps it within this composition; [SkinSchemes] keeps it across
 * all of them.
 *
 * A live skin was made from a scheme and uses that one; [dark] and [style] do not apply to it.
 */
@Composable
fun rememberSkinColorScheme(
    skin: Skin,
    dark: Boolean = true,
    style: SkinSchemeStyle = SkinSchemeStyle.VIBRANT,
): ColorScheme = remember(skin, dark, style) { skin.liveScheme ?: SkinSchemes.of(skin, dark, style) }
