// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.skin

/*
 * Packed 0xRRGGBB, the way the skin template and its color math pass a color around: an Int
 * without alpha, because a sheet has no transparency.
 */

/** Three channels into one packed 0xRRGGBB. */
internal fun rgb(
    r: Int,
    g: Int,
    b: Int,
) = (r shl 16) or (g shl 8) or b

internal fun red(c: Int) = (c shr 16) and 0xFF

internal fun green(c: Int) = (c shr 8) and 0xFF

internal fun blue(c: Int) = c and 0xFF

/** Opaque ARGB, which is what a Bitmap wants. */
internal fun argb(c: Int) = (0xFF shl 24) or (c and 0xFFFFFF)
