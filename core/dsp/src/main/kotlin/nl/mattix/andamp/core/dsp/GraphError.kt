// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.core.dsp

/**
 * Why a graph was refused: a code for tests to assert on and a sentence for people to read.
 *
 * Refusal is a load failure only. Playback continues, the effect does not appear, and the
 * reason is shown; see docs/dsp-plugin-spec.md section 8.
 */
sealed class GraphError(
    val code: String,
    val message: String,
) {
    class UnknownApi(
        api: Int,
    ) : GraphError("unknownApi", "This plug-in wants api $api; this player implements ${GraphSpec.API}.")

    class UnknownOption(
        node: Int,
        primitive: Primitive,
        key: String,
        didYouMean: String?,
    ) : GraphError(
            "unknownOption",
            "Node $node (${primitive.name.lowercase()}) has no option '$key'" +
                (didYouMean?.let { ". Did you mean '$it'?" } ?: "."),
        )

    class MissingOption(
        node: Int,
        primitive: Primitive,
        key: String,
    ) : GraphError("missingOption", "Node $node (${primitive.name.lowercase()}) needs '$key'.")

    class EdgeInConstSlot(
        node: Int,
        key: String,
    ) : GraphError("edgeInConstSlot", "Node $node's '$key' has to be known when the graph is built.")

    class ConstExpected(
        node: Int,
        key: String,
    ) : GraphError("constExpected", "Node $node's '$key' is a constant, not a connection.")

    class ConstTypeMismatch(
        node: Int,
        key: String,
        wanted: String,
    ) : GraphError("constTypeMismatch", "Node $node's '$key' has to be $wanted.")

    class UnknownEnum(
        node: Int,
        key: String,
        value: String,
        allowed: Set<String>,
    ) : GraphError("unknownEnum", "Node $node's $key '$value' is not one of ${allowed.sorted().joinToString()}.")

    class MissingNode(
        node: Int,
        key: String,
        referenced: Int,
        count: Int,
    ) : GraphError("missingNode", "Node $node's '$key' points at node $referenced, and there are $count nodes.")

    class SelfReference(
        node: Int,
        key: String,
    ) : GraphError("selfReference", "Node $node's '$key' points at itself.")

    class ChannelOutOfRange(
        node: Int,
        channel: Int,
        channels: Int,
    ) : GraphError("channelOutOfRange", "Node $node reads channel $channel of $channels.")

    class ParamOutOfRange(
        node: Int,
        index: Int,
        count: Int,
    ) : GraphError("paramOutOfRange", "Node $node reads parameter $index, and there are $count.")

    class OutputArity(
        outputs: Int,
        channels: Int,
    ) : GraphError("outputArity", "The graph names $outputs outputs for $channels channels.")

    class OutputNodeMissing(
        channel: Int,
        node: Int,
    ) : GraphError("outputNodeMissing", "Channel $channel is fed by node $node, which does not exist.")

    class RateTagMismatch(
        node: Int,
        declared: Rate,
        inferred: Rate,
    ) : GraphError("rateTagMismatch", "Node $node says it runs at $declared and it runs at $inferred.")

    class UnmatchedTap(
        name: String,
        missing: String,
    ) : GraphError("unmatchedTap", "The feedback tap '$name' has no $missing.")

    class DuplicateTap(
        name: String,
        half: String,
    ) : GraphError("duplicateTap", "The feedback tap '$name' has more than one $half.")

    class TapOnNonDelay(
        node: Int,
        line: Int,
    ) : GraphError("tapOnNonDelay", "Node $node taps node $line, which is not a delay.")

    class UndelayedFeedback(
        cycle: List<Int>,
    ) : GraphError("undelayedFeedback", "Nodes ${cycle.joinToString(" -> ")} feed back with no delay between them.")

    class UnstableFeedback(
        cycle: List<Int>,
        gain: Float,
    ) : GraphError("unstableFeedback", "Nodes ${cycle.joinToString(" -> ")} loop at a gain of $gain.")

    class CeilingOutOfRange(
        node: Int,
        ceiling: Float,
    ) : GraphError("ceilingOutOfRange", "Node $node's ceiling of $ceiling is outside what the engine will hold.")

    class BadMaxTime(
        node: Int,
        maxTime: Float,
    ) : GraphError("badMaxTime", "Node $node asks for a delay line of $maxTime frames, which is not a line at all.")

    class DelayFramesMismatch(
        declared: Int,
        actual: Int,
    ) : GraphError("delayFramesMismatch", "The graph declares $declared frames of delay and its nodes need $actual.")

    class NodeBudget(
        nodes: Int,
        allowed: Int,
    ) : GraphError("nodeBudget", "The graph has $nodes nodes and $allowed are allowed.")

    class DelayMemoryBudget(
        frames: Int,
        allowed: Int,
    ) : GraphError("delayMemoryBudget", "The graph wants $frames frames of delay and $allowed are allowed.")
}

/** The limits a graph is held to: a node count and a total of delay memory in frames. */
data class Budgets(
    val maxNodes: Int = 512,
    val maxDelayFrames: Int = 262_144,
) {
    companion object {
        val DEFAULT = Budgets()
    }
}
