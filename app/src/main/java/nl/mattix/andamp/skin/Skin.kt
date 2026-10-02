// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.skin

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap

/** The sprite sheets a classic skin is made of (zip member base names). */
enum class Sheet(
    val baseName: String,
) {
    MAIN("main"),
    CBUTTONS("cbuttons"),
    TITLEBAR("titlebar"),
    SHUFREP("shufrep"),
    POSBAR("posbar"),
    VOLUME("volume"),
    BALANCE("balance"),
    MONOSTER("monoster"),
    PLAYPAUS("playpaus"),
    NUMBERS("numbers"),
    NUMS_EX("nums_ex"),
    TEXT("text"),
    EQMAIN("eqmain"),

    /** The equalizer's extra art: the shaded bar with its volume and balance sliders. */
    EQ_EX("eq_ex"),
    PLEDIT("pledit"),

    /** The generic window frame Winamp used for its extra windows (video, Milkdrop). */
    GEN("gen"),
}

@Immutable
class Skin(
    private val sheets: Map<Sheet, ImageBitmap>,
    /** True when the skin ships no BALANCE.BMP and VOLUME.BMP art is reused (authentic Winamp fallback). */
    val balanceUsesVolume: Boolean,
    val pledit: PleditStyle,
    val visColors: List<Color>,
    val name: String,
    /** Title font of the generic window frame; null when the skin ships no GEN.BMP. */
    val genTitleFont: GenTitleFont? = null,
    /**
     * True when GEN.BMP came from this skin and not the fallback. Floating windows use it to pick
     * chrome that matches.
     */
    val ownsGenArt: Boolean = false,
    /**
     * The shape this skin cuts its player and equalizer into (REGION.TXT). Empty for most skins,
     * which are plain rectangles.
     */
    val regions: RegionTxt.Regions = RegionTxt.Regions.NONE,
    /** The readme text the artist packed with the skin, if any. */
    val readme: String? = null,
    /**
     * The phone's color scheme this skin was built from, when it was built from one; see
     * [SkinTemplate]. Null for every skin that came from a file. The modern chrome around a live
     * skin uses this scheme directly.
     */
    val liveScheme: ColorScheme? = null,
) {
    operator fun get(sheet: Sheet): ImageBitmap =
        sheets[sheet] ?: error("Sheet $sheet missing — loader must guarantee all required sheets")

    fun getOrNull(sheet: Sheet): ImageBitmap? = sheets[sheet]

    /** The same art, cut to a different shape. */
    fun withRegions(regions: RegionTxt.Regions): Skin =
        // named arguments, so every field is visibly carried over
        Skin(
            sheets = sheets,
            balanceUsesVolume = balanceUsesVolume,
            pledit = pledit,
            visColors = visColors,
            name = name,
            genTitleFont = genTitleFont,
            ownsGenArt = ownsGenArt,
            regions = regions,
            readme = readme,
            liveScheme = liveScheme,
        )

    val hasNumsEx: Boolean get() = Sheet.NUMS_EX in sheets

    /** A generic window can only be drawn when the skin ships GEN.BMP. */
    val hasGenWindow: Boolean get() = Sheet.GEN in sheets && genTitleFont != null

    /** Sheet to read digit/minus sprites from (NUMS_EX overrides NUMBERS). */
    val numbersSheet: ImageBitmap get() = sheets[Sheet.NUMS_EX] ?: get(Sheet.NUMBERS)

    /** Sheet to read balance background/thumb from. */
    val balanceSheet: ImageBitmap get() = if (balanceUsesVolume) get(Sheet.VOLUME) else get(Sheet.BALANCE)

    val balanceBackground: Sprite
        get() =
            if (balanceUsesVolume) {
                SpriteMap.MAIN_BALANCE_BACKGROUND_FROM_VOLUME
            } else {
                SpriteMap.MAIN_BALANCE_BACKGROUND
            }
}
