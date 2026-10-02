// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.core.plugin

import nl.mattix.andamp.core.dsp.GraphParam
import nl.mattix.andamp.core.dsp.GraphSpec
import nl.mattix.andamp.core.model.Preset
import nl.mattix.andamp.core.model.UiNode

/**
 * What a plug-in is once its Lua has run.
 *
 * Plain data: the script runs once, off the audio thread, and nothing it created stays
 * reachable. This can be cached, compared and handed to the compiler, and nothing after it
 * depends on the Lua runtime.
 */
data class PluginSpec(
    val id: String,
    val name: String,
    val version: String,
    val author: String,
    val about: String,
    val params: List<GraphParam>,
    val graph: GraphSpec,
    /**
     * How the plug-in wants its controls laid out, or empty when it has no `ui` block. The
     * host then lays the parameters out in declaration order (docs/dsp-plugin-spec.md
     * section 3).
     */
    val ui: List<UiNode> = emptyList(),
    /** Named settings; see [Preset]. */
    val presets: List<Preset> = emptyList(),
    /**
     * Keys in its `param` tables that the host does not read, as `<id>.<key>`. They are
     * ignored (docs/dsp-plugin-spec.md section 2) and listed here for tests and logs.
     */
    val unread: List<String> = emptyList(),
)

/** Why a plug-in was refused: a code to assert on and a sentence to read. */
sealed class PluginError(
    val code: String,
    val message: String,
) {
    class Syntax(
        detail: String,
    ) : PluginError("syntax", "The plug-in could not be read: $detail")

    class Failed(
        detail: String,
    ) : PluginError("failed", "The plug-in stopped while it was loading: $detail")

    class TooLong(
        bytes: Int,
        allowed: Int,
    ) : PluginError("tooLong", "The plug-in is $bytes bytes and $allowed are allowed.")

    class TooSlow(
        millis: Long,
    ) : PluginError("tooSlow", "The plug-in was still loading after $millis ms.")

    class MissingMetadata(
        key: String,
    ) : PluginError("missingMetadata", "The plug-in does not say its $key.")

    class MissingDefault(
        id: String,
    ) : PluginError("missingDefault", "The control '$id' does not say where it starts.")

    class DefaultOutOfRange(
        id: String,
        default: Float,
        min: Float,
        max: Float,
    ) : PluginError("defaultOutOfRange", "The control '$id' starts at $default, outside its own $min to $max.")

    /** A group is switched by a toggle the graph never reads. */
    class UnreadGate(
        group: String,
        control: String,
    ) : PluginError(
            "unreadGate",
            "The group '$group' is switched by '$control', which its graph never reads: " +
                "the switch would gray its controls and change nothing that can be heard.",
        )

    /** The plug-in asks for a host version other than this one. */
    class WrongApi(
        asked: Int,
        here: Int,
    ) : PluginError("wrongApi", "The plug-in asks for host version $asked, and this host is version $here.")

    /** A unit that is not one of the LV2 symbols the host renders. */
    class UnknownUnit(
        control: String,
        unit: String,
    ) : PluginError(
            "unknownUnit",
            "The control '$control' is in '$unit', which is not one of the units a host knows how to write.",
        )

    class BadGraph(
        detail: String,
    ) : PluginError("badGraph", "The plug-in built a graph that will not run: $detail")
}
