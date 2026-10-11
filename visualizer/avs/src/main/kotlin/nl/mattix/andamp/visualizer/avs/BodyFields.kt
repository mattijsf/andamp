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
internal fun BodyReader.pairBlend(whenMissing: Int = 0): AvsBlendMode? {
    // a body that ends before the first int takes [whenMissing] for it, the
    // value the component starts with: 0 is replace, 2 the render mode
    val low = int32Or(whenMissing)
    val high = int32Or(0)
    return when {
        high == 1 -> AvsBlendMode.FIFTY_FIFTY
        low == 1 -> AvsBlendMode.ADDITIVE
        low == 2 -> null
        else -> AvsBlendMode.REPLACE
    }
}

/**
 * A color list as vis_avs loads one: a count, one white color when the body
 * ends before it, then as many colors as the body still holds. A color the
 * body does not reach is black. A count above 16 leaves [whenTooMany] colors
 * (the components differ: none, or the one white), and a negative count draws
 * with the first color alone.
 */
internal fun BodyReader.colourListOr(whenTooMany: Int = 0): List<Int> {
    val white = AvsFrame.fromConfig(WHITE)
    if (!has(INT_SIZE)) return listOf(white)
    val count = int32()
    return when {
        count > MAX_COLOURS -> {
            List(whenTooMany) { white }
        }

        count < 0 -> {
            listOf(white)
        }

        else -> {
            List(count) { index ->
                if (has(INT_SIZE)) {
                    AvsFrame.fromConfig(int32())
                } else if (index ==
                    0
                ) {
                    white
                } else {
                    AvsFrame.OPAQUE
                }
            }
        }
    }
}

private const val MAX_COLOURS = 16
private const val INT_SIZE = 4
private const val WHITE = 0xFFFFFF
