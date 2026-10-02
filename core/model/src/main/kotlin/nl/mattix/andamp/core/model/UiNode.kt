// SPDX-License-Identifier: Apache-2.0

package nl.mattix.andamp.core.model

/**
 * One item in a plug-in's layout: a control bound to a parameter, a line of text, or a
 * titled card holding more of them. There is no drawing and no positioning, so the host can
 * restyle and resize the layout and read it out loud.
 */
sealed interface UiNode {
    /** Which control a parameter is given. */
    enum class Kind {
        SLIDER,
        TOGGLE,
        CHOICE,
    }

    /**
     * A parameter, drawn.
     *
     * [param] indexes [EffectSpec.params]; [label] and [help] override what that parameter
     * declared.
     */
    data class Control(
        val param: Int,
        val kind: Kind,
        val label: String? = null,
        val help: String? = null,
    ) : UiNode

    /** A line of text. */
    data class Label(
        val text: String,
    ) : UiNode

    /**
     * A titled card. [name] may be null for a card with no title.
     *
     * [enabledBy] names a toggle parameter that switches the whole group. The
     * host draws that toggle in the group's header and grays the body.
     */
    data class Group(
        val name: String?,
        val items: List<UiNode>,
        val enabledBy: Int? = null,
        val help: String? = null,
    ) : UiNode
}
