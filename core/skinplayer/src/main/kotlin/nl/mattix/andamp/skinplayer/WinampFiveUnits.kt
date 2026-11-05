// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.skinplayer

import kotlin.math.roundToInt

/**
 * Winamp 5's scales, as a modern skin's scripts read and write the player, beside the
 * [PlayerState] scales of Winamp 2: the volume and the equalizer 0..[TOP] (the equalizer flat at
 * [FLAT]), the balance -[PAN_REACH]..[PAN_REACH]. Each map holds the ends and the flat, and a value
 * on the state's scale comes back to itself through the skin's.
 */
public object WinampFiveUnits {
    /** The top of the skin's scale for the volume and the equalizer. */
    public const val TOP: Int = 255

    /** The equalizer's flat on the skin's scale. */
    public const val FLAT: Int = 128

    /** How far the balance reaches either way on the skin's scale. */
    public const val PAN_REACH: Int = 127

    /** The state's volume, 0..[PlayerState.VOLUME_MAX], on the skin's 0..[TOP]. */
    public fun fromPercent(percent: Int): Int = (percent * TOP / PlayerState.VOLUME_MAX.toFloat()).roundToInt()

    /** A skin volume as the state shows it once the player has it, rounded as the player reports it. */
    public fun toPercent(v: Int): Int = (v / TOP.toFloat() * PlayerState.VOLUME_MAX).roundToInt()

    /**
     * A state band, 0..[PlayerState.EQ_MAX] flat at [PlayerState.EQ_CENTER], on the skin's 0..[TOP]
     * flat at [FLAT]. Below flat and above it the slopes differ, so the ends and the flat land on
     * each other.
     */
    public fun fromEq(v: Int): Int =
        if (v <= PlayerState.EQ_CENTER) {
            (v * FLAT / PlayerState.EQ_CENTER.toFloat()).roundToInt()
        } else {
            FLAT +
                ((v - PlayerState.EQ_CENTER) * (TOP - FLAT) / (PlayerState.EQ_MAX - PlayerState.EQ_CENTER).toFloat()).roundToInt()
        }

    /** A skin band on the state's scale; the inverse of [fromEq]. */
    public fun toEq(v: Int): Int =
        if (v <= FLAT) {
            (v * PlayerState.EQ_CENTER / FLAT.toFloat()).roundToInt()
        } else {
            PlayerState.EQ_CENTER +
                ((v - FLAT) * (PlayerState.EQ_MAX - PlayerState.EQ_CENTER) / (TOP - FLAT).toFloat()).roundToInt()
        }

    /** A state balance, -[PlayerState.BALANCE_MAX]..[PlayerState.BALANCE_MAX], on the skin's scale. */
    public fun fromBalance(balance: Int): Int = (balance * PAN_REACH / PlayerState.BALANCE_MAX.toFloat()).roundToInt()

    /** A skin balance as the state shows it. */
    public fun toBalance(pan: Int): Int = (pan * PlayerState.BALANCE_MAX / PAN_REACH.toFloat()).roundToInt()
}
