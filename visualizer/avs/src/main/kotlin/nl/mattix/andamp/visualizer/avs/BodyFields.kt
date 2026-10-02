// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.visualizer.avs

// The field shapes that recur across component bodies. Layouts transcribed from
// grandchild/AVS-File-Decoder (MIT); see NOTICE.md.

/**
 * The 4-byte blend most Trans components use: `Map4 {0 REPLACE, 1 ADDITIVE,
 * 2 FIFTY_FIFTY}`.
 */
internal fun shortBlend(code: Int) =
    when (code) {
        1 -> AvsBlendMode.ADDITIVE
        2 -> AvsBlendMode.FIFTY_FIFTY
        else -> AvsBlendMode.REPLACE
    }

/**
 * The 4-value form of [shortBlend], where 3 means the current render mode:
 * null here, resolved against [AvsRenderState.renderBlend] at draw time.
 */
internal fun shortBlendOrDefault(code: Int) = if (code == 3) null else shortBlend(code)

/**
 * The 8-byte blend, the decoder's `Map8`: two int32s, of which the high word
 * being 1 means fifty-fifty. Null is the format's DEFAULT (Clear Screen and
 * Timescope offer it): the preset's current render mode, resolved at draw time.
 */
internal fun BodyReader.pairBlend(): AvsBlendMode? {
    val low = int32()
    val high = int32()
    return when {
        high == 1 -> AvsBlendMode.FIFTY_FIFTY
        low == 1 -> AvsBlendMode.ADDITIVE
        low == 2 -> null
        else -> AvsBlendMode.REPLACE
    }
}

/** A count and then that many colors. A count outside 0..16 reads as no colors. */
internal fun BodyReader.colourList(): List<Int> {
    val count = int32()
    if (count !in 0..MAX_COLOURS) return emptyList()
    return List(count) { AvsFrame.fromConfig(int32()) }
}

private const val MAX_COLOURS = 16
