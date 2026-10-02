// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.backend.media3

/**
 * Winamp's balance law: panning attenuates the channel panned away from and
 * leaves the other at full scale. It is not a constant-power pan: sliding to
 * one side makes the mix quieter. Center is unity on both channels and a hard
 * side silences its opposite.
 *
 * [balance] runs -1 (hard left) through 0 (center) to +1 (hard right).
 */
object BalanceGain {
    fun left(balance: Float): Float = if (balance > 0f) 1f - balance.coerceAtMost(1f) else 1f

    fun right(balance: Float): Float = if (balance < 0f) 1f + balance.coerceAtLeast(-1f) else 1f

    /**
     * Gain for [channel] of a [channelCount]-channel frame. One channel is not
     * panned; with more than two, the even channels count as left and the odd
     * as right.
     */
    fun forChannel(
        channel: Int,
        channelCount: Int,
        balance: Float,
    ): Float =
        when {
            channelCount < 2 -> 1f
            channel % 2 == 0 -> left(balance)
            else -> right(balance)
        }
}
