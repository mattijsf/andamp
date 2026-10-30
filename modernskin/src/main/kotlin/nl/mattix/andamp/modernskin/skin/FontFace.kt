// SPDX-License-Identifier: GPL-3.0-or-later
package nl.mattix.andamp.modernskin.skin

/**
 * Where the glyphs of a text come from, as the engine chooses them ([FontFace.of]); every
 * renderer and every text measure goes by the same choice.
 */
sealed interface FontFace {
    /** The skin's font [font] ([FontDef.file]); the renderer makes it bold itself when [fakeBold]. */
    data class SkinFile(
        val font: FontDef,
        val fakeBold: Boolean,
    ) : FontFace

    /**
     * A free font that stands in for a Windows system font the skin names without a file: [family]
     * (DejaVu Sans Condensed for Tahoma, metric-compatible Liberation Sans for Arial), in its bold
     * cut when [bold], so it is never made bold by the renderer. [file] is where Andamp's default
     * set keeps it ([AndampDefaultSet]); a host may carry it elsewhere.
     */
    data class StandIn(
        val family: String,
        val bold: Boolean,
    ) : FontFace {
        val file: String get() = "fonts/$family${if (bold) "-Bold" else ""}.ttf"
    }

    /** No font named: the platform's default, made bold by the renderer when [fakeBold]. */
    data class Default(
        val fakeBold: Boolean,
    ) : FontFace

    companion object {
        /** Windows fonts skins name without a file, and the free font that stands in. */
        val STAND_INS: Map<String, String> = mapOf("tahoma" to "DejaVuSansCondensed", "arial" to "LiberationSans")

        /** The face [font] is drawn and measured with, [bold] or not. */
        fun of(
            font: FontDef?,
            bold: Boolean,
        ): FontFace {
            if (font == null) return Default(bold)
            val family = STAND_INS[font.id].takeIf { font.file.isEmpty() } ?: return SkinFile(font, bold)
            return StandIn(family, bold)
        }
    }
}
