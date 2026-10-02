// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.skin

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap

/**
 * Turns decoded sheets and text into a [Skin], applying Winamp's rules about what a skin may leave
 * out and what it borrows when it does. Both ways a skin arrives share it: [SkinLoader] reads a
 * `.wsz`, and the live path rebuilds one from the phone's palette with [SkinTemplate].
 */
internal object SkinAssembly {
    /**
     * What a skin brought itself, before anything is borrowed: the sheets that decoded, and the
     * text files.
     */
    class Parts(
        val sheets: Map<Sheet, ImageBitmap>,
        val pledit: String?,
        val viscolor: String?,
        val region: String?,
        val readme: String?,
    )

    /**
     * Assembles [parts] into a skin, borrowing from [fallback] per sheet the
     * way Winamp did. With no fallback every required sheet must be present
     * or this throws.
     */
    fun assemble(
        parts: Parts,
        fallback: Skin?,
        name: String,
        liveScheme: ColorScheme? = null,
    ): Skin {
        val sheets = mutableMapOf<Sheet, ImageBitmap>()
        val balanceUsesVolume = resolveSheets(sheets, parts.sheets, fallback, name)

        // balance may still resolve via a fallback skin that has real balance art
        if (balanceUsesVolume && Sheet.VOLUME !in sheets) {
            error("Skin '$name' has neither balance nor volume sheet")
        }

        val pledit = parts.pledit?.let(PleditTxt::parse) ?: fallback?.pledit ?: PleditStyle.DEFAULT
        val visColors = VisColorTxt.parse(parts.viscolor, fallback = fallback?.visColors ?: VisColorTxt.DEFAULT)

        // the shape the skin cuts its windows into; without one every window is a rectangle
        val regions = RegionTxt.parse(parts.region)

        // GEN.BMP's title font is variable-width and per-skin, so it is scanned out of the sheet
        // (see GenTitleFont). A skin borrowing the fallback's sheet borrows its font too.
        val scanned =
            parts.sheets[Sheet.GEN]?.let { gen ->
                val pixels = gen.toPixelMap()
                GenTitleFont.scan(gen.width, gen.height) { x, y -> pixels[x, y].toArgb() }
            }
        // A stub GEN.BMP carries neither the letter strips nor the frame art, so shipping the file
        // is not owning usable chrome.
        val ownGen = scanned != null
        return Skin(
            sheets,
            balanceUsesVolume,
            pledit,
            visColors,
            name,
            scanned ?: fallback?.genTitleFont,
            ownsGenArt = ownGen,
            regions = regions,
            readme = parts.readme,
            liveScheme = liveScheme,
        )
    }

    /**
     * Fills [sheets] from what the skin brought, falling back per sheet the
     * way Winamp did. Returns true when balance art had to be borrowed from
     * volume.
     */
    private fun resolveSheets(
        sheets: MutableMap<Sheet, ImageBitmap>,
        own: Map<Sheet, ImageBitmap>,
        fallback: Skin?,
        name: String,
    ): Boolean {
        var balanceUsesVolume = false
        for (sheet in Sheet.entries) {
            val decoded = own[sheet]
            if (decoded != null) {
                sheets[sheet] = decoded
                continue
            }
            when (sheet) {
                // Winamp reuses volume art when balance.bmp is absent
                Sheet.BALANCE -> {
                    balanceUsesVolume = true
                }

                // nums_ex is an optional override
                Sheet.NUMS_EX -> {
                    Unit
                }

                // GEN.BMP only exists in later skins; borrow the fallback's so generic windows
                // still have chrome
                Sheet.GEN -> {
                    fallback?.getOrNull(Sheet.GEN)?.let { sheets[Sheet.GEN] = it }
                }

                else -> {
                    sheets[sheet] = fallback?.getOrNull(sheet)
                        ?: error("Skin '$name' is missing required sheet ${sheet.baseName} and no fallback skin is available")
                }
            }
        }
        return balanceUsesVolume
    }
}
