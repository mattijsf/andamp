// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.core.dsp

/**
 * Reads a node's declared arguments after the validator has checked their types. The casts
 * are unchecked: a constant of the wrong kind here would be a compiler bug.
 */
internal object Args {
    fun number(
        at: NodeSpec,
        key: String,
    ) = (at.consts[key] as ConstArg.Num).value

    fun optionalNumber(
        at: NodeSpec,
        key: String,
        fallback: Float,
    ) = (at.consts[key] as? ConstArg.Num)?.value ?: fallback

    fun text(
        at: NodeSpec,
        key: String,
    ) = (at.consts[key] as ConstArg.Text).value

    fun optionalText(
        at: NodeSpec,
        key: String,
        fallback: String,
    ) = (at.consts[key] as? ConstArg.Text)?.value ?: fallback
}
