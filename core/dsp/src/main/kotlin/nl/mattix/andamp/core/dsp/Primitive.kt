// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.core.dsp

/** What a `math` node computes. */
enum class MathFn {
    EXP2,
    LOG2,
    SIN,
    COS,
    TAN,
    TANH,
    SQRT,
    ABS,
    RECIP,
    NEG,
    FLOOR,
    ROUND,
}

/** RBJ cookbook shapes. `gainDb` applies to the last three only. */
enum class BiquadKind {
    LOWPASS,
    HIGHPASS,
    BANDPASS,
    NOTCH,
    ALLPASS,
    PEAKING,
    LOWSHELF,
    HIGHSHELF,
}

/**
 * The catalog of primitives, as a table.
 *
 * Every rule the validator applies about arguments is read from here: what may be an edge,
 * what must be known at build, which const values are enumerated, and whether the primitive
 * carries state and how much. docs/dsp-plugin-spec.md section 6 is the same table for plug-in
 * authors.
 *
 * [stateful] keeps a node at audio rate however it is fed: a node whose output depends on its
 * own past cannot be evaluated once per control tick.
 */
@Suppress("LongParameterList") // one parameter per rule the validator reads
enum class Primitive(
    val edgeKeys: Set<String> = emptySet(),
    val constKeys: Set<String> = emptySet(),
    val enums: Map<String, Set<String>> = emptyMap(),
    /** Constants that are words and are not chosen from a list. A tap's name is the only one. */
    val textKeys: Set<String> = emptySet(),
    /** Keys that may be left out; the engine has a default for each. */
    val optional: Set<String> = emptySet(),
    val stateful: Boolean = false,
    val fixedRate: Rate? = null,
    val stateWords: Int = 0,
) {
    /** The incoming audio. Any channel may be read from anywhere in the graph. */
    INPUT(constKeys = setOf("ch"), fixedRate = Rate.AUDIO),

    /** A control's smoothed value. */
    PARAM(constKeys = setOf("index"), fixedRate = Rate.CONTROL),

    ADD(edgeKeys = setOf("a", "b")),
    SUB(edgeKeys = setOf("a", "b")),
    MUL(edgeKeys = setOf("a", "b")),
    DIV(edgeKeys = setOf("a", "b")),
    MIN(edgeKeys = setOf("a", "b")),
    MAX(edgeKeys = setOf("a", "b")),

    /** `t` 0 gives `a`, 1 gives `b`, and both ends are exact. */
    CROSSFADE(edgeKeys = setOf("a", "b", "t")),

    /** Hard bounds. */
    CLIP(edgeKeys = setOf("input", "min", "max")),

    /** Zeroes anything not finite, and anything small enough to go denormal. */
    SANITISE(edgeKeys = setOf("input")),

    MATH(edgeKeys = setOf("input"), constKeys = setOf("fn"), enums = mapOf("fn" to MATH_FNS)),

    /** The one place decibels become amplitude. */
    GAIN(edgeKeys = setOf("input", "db")),

    BIQUAD(
        edgeKeys = setOf("input", "freq", "q", "gainDb"),
        constKeys = setOf("kind"),
        enums = mapOf("kind" to BIQUAD_KINDS),
        // gainDb is used by the peaking and shelving kinds only
        optional = setOf("gainDb"),
        stateful = true,
        // b0 b1 b2 a1 a2, then x1 x2 y1 y2
        stateWords = 9,
    ),

    /** One pole with a raw coefficient. */
    ONEPOLE(edgeKeys = setOf("input", "coeff"), stateful = true, stateWords = 1),

    /** One first-order all-pass section, bounded by its declared ceiling. */
    ALLPASS1(
        edgeKeys = setOf("input", "coeff"),
        constKeys = setOf("ceiling"),
        stateful = true,
        stateWords = 2,
    ),

    /** `maxTime` sizes the line, so it cannot be an edge. */
    DELAY(
        edgeKeys = setOf("input", "time"),
        constKeys = setOf("maxTime", "interp"),
        enums = mapOf("interp" to setOf("none", "linear")),
        optional = setOf("interp"),
        stateful = true,
    ),

    /** A second reader on another node's delay line. */
    TAP(
        edgeKeys = setOf("time"),
        constKeys = setOf("line", "interp"),
        enums = mapOf("interp" to setOf("none", "linear")),
        optional = setOf("interp"),
        stateful = true,
    ),

    LFO(
        edgeKeys = setOf("rate", "phase"),
        constKeys = setOf("shape"),
        enums = mapOf("shape" to setOf("sine", "triangle")),
        optional = setOf("phase"),
        stateful = true,
        stateWords = 1,
    ),

    ENVELOPE(
        edgeKeys = setOf("input"),
        constKeys = setOf("attack", "release", "kind"),
        enums = mapOf("kind" to setOf("peak", "rms")),
        optional = setOf("kind"),
        stateful = true,
        stateWords = 1,
    ),

    /**
     * White noise, -1..1, from a generator seeded by `seed`. The same seed gives the same
     * noise on every run and on both runners. Two nodes with one seed produce the same noise.
     */
    NOISE(constKeys = setOf("seed"), optional = setOf("seed"), stateful = true, stateWords = 1),

    /** Bounded and smooth. */
    SOFTCLIP(edgeKeys = setOf("input", "drive"), constKeys = setOf("knee"), optional = setOf("knee")),

    /** The write half of a named feedback pair. The engine holds the value for one frame. */
    TAPIN(
        edgeKeys = setOf("source"),
        constKeys = setOf("name"),
        textKeys = setOf("name"),
        stateful = true,
        stateWords = 1,
    ),

    /** The read half. Shares its partner's state word. */
    TAPOUT(constKeys = setOf("name"), textKeys = setOf("name"), stateful = true),
    ;

    /** Every key this primitive accepts. */
    val keys: Set<String> get() = edgeKeys + constKeys

    /** The keys a node must carry. */
    val required: Set<String> get() = keys - optional

    /** True when this constant is a word: an enumerated one or a free name. */
    fun isText(key: String) = key in enums || key in textKeys
}

/** An enum's names in lowercase, as a graph spells them. */
private inline fun <reified E : Enum<E>> names(): Set<String> = enumValues<E>().map { it.name.lowercase() }.toSet()

private val MATH_FNS = names<MathFn>()

private val BIQUAD_KINDS = names<BiquadKind>()
