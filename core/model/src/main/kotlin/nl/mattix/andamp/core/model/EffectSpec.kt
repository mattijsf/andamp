// SPDX-License-Identifier: Apache-2.0

package nl.mattix.andamp.core.model

import kotlin.math.abs

/**
 * What a control is: enough for a host to render it and for an effect to read it.
 *
 * A plug-in's `param.number` declaration takes this shape (docs/dsp-plugin-spec.md), and
 * the built-in effects declare themselves the same way.
 */
data class ParamSpec(
    val id: String,
    val name: String,
    val min: Float = 0f,
    val max: Float = 1f,
    val default: Float = 0f,
    /** LV2 unit symbol: "", "pc", "hz", "ms", "db". Display only. */
    val unit: String = "",
    /** The value the slider's center should land on; null for linear travel. */
    val mid: Float? = null,
    /** Label to value, for a choice. Null for a plain number. */
    val choices: Map<String, Float>? = null,
    /**
     * Whether this reads as a switch.
     *
     * The value is still a number: it reaches the effect as 0 or 1 and is smoothed like any
     * other, so `crossfade(dry, wet, it)` is a click-free bypass (docs/dsp-plugin-spec.md
     * section 2).
     */
    val toggle: Boolean = false,
    /**
     * How the value reads, when the number the effect wants and the number shown differ
     * (docs/dsp-plugin-spec.md section 2).
     *
     * [displayScale] multiplies for the readout only: a mix the effect reads as 0..1 can
     * show as 0..100. [displayDecimals] of -1 leaves it to the host. [displayZero] is the
     * text shown at the bottom of the travel, when that is a state and not a quantity.
     */
    val displayScale: Float = 1f,
    val displayDecimals: Int = -1,
    val displayZero: String = "",
    /**
     * Whether changing this rebuilds the effect.
     *
     * A value parameter retunes existing nodes; a structural one chooses which nodes exist,
     * as the modulation's mode does. The host answers a structural change by building the
     * new arrangement off the audio thread and fading between the two. Only built-in effects
     * have one: a plug-in's `build` runs once per format, so its parameters are all live
     * values.
     */
    val structural: Boolean = false,
    /**
     * What this control does, beyond what its name says. The host shows it behind a small
     * mark; blank means no mark.
     */
    val help: String = "",
    /**
     * Values a drag lands on when it passes within reach of them (see [snapped]); empty for
     * none. For positions a listener aims at, such as a center pan or a unity gain, which a
     * touch slider cannot hit by hand.
     */
    val snap: List<Float> = emptyList(),
) {
    val isChoice get() = choices != null

    /**
     * [value] pulled onto the nearest snap point it is within [SNAP_REACH] of. The reach is
     * a fraction of the whole travel, so the detent feels the same on every range.
     */
    fun snapped(value: Float): Float {
        if (snap.isEmpty()) return value
        val reach = (max - min) * SNAP_REACH
        val nearest = snap.minByOrNull { abs(it - value) } ?: return value
        // a snap point outside the range is clamped, so a stage never receives a value its
        // own declaration rules out
        return if (abs(nearest - value) <= reach) nearest.coerceIn(min, max) else value
    }

    private companion object {
        /**
         * Three percent of the travel to either side. A much wider reach would leave the
         * values beside a snap point unreachable.
         */
        const val SNAP_REACH = 0.03f
    }
}

/** An effect: its identity, its name in the list, and its controls. */
data class EffectSpec(
    val id: String,
    val name: String,
    val description: String = "",
    val params: List<ParamSpec> = emptyList(),
    /**
     * How this effect asked to be laid out, or empty when it did not ask.
     *
     * Empty means the host lays every parameter out in the order it was declared
     * (docs/dsp-plugin-spec.md section 3). Nodes refer to [params] by index.
     */
    val ui: List<UiNode> = emptyList(),
    /**
     * Named settings, offered above the controls.
     *
     * A preset need not mention every control; what it leaves out stays where the listener
     * had it (docs/dsp-plugin-spec.md section 4).
     */
    val presets: List<Preset> = emptyList(),
) {
    /** Every parameter at its declared default, for a slot that has never been touched. */
    val defaults: ParamValues get() = ParamValues(params.associate { it.id to it.default })
}

/** A named set of values, keyed by parameter id. */
data class Preset(
    val name: String,
    val values: Map<String, Float>,
)

/**
 * The effects the player ships written in Kotlin. They are separate entries so that the
 * listener can order them: reverb before modulation sounds different from reverb after it.
 */
object BuiltInEffects {
    /**
     * The id of the Preamp, which ships as a Lua plug-in and is not in [all]. The rack keeps
     * it first, so every effect after it works with the level it sets; see
     * [RackSettings.withFirst].
     */
    const val PREAMP = "nl.mattix.andamp.preamp"

    const val KARAOKE = "andamp.karaoke"
    const val MODULATION = "andamp.modulation"
    const val REVERB = "andamp.reverb"

    /** What the decay control can be set to, in seconds. */
    const val MIN_DECAY_SECONDS = 0.3f
    const val MAX_DECAY_SECONDS = 6f

    val karaoke =
        EffectSpec(
            id = KARAOKE,
            name = "Karaoke",
            description =
                "Mid/side vocal cancellation: the side signal, with the mid low-passed at 60–500 Hz " +
                    "(12–24 dB/oct Butterworth) to keep the bass",
            params =
                listOf(
                    ParamSpec(
                        "level",
                        "Level",
                        default = 1f,
                        unit = "pc",
                        help = "How much of the cancellation is used. 0 hands back the original audio untouched.",
                    ),
                    ParamSpec(
                        "filter",
                        "Filter",
                        default = 0.35f,
                        help =
                            "Cutoff of the mono low end that is kept, 60 Hz to 500 Hz. " +
                                "Above a male voice's fundamental it keeps the bass but also some of the singer.",
                    ),
                    ParamSpec(
                        "band",
                        "Band",
                        default = 0.5f,
                        help =
                            "Rolloff of that kept low end, 24 dB/oct at 0 to 12 dB/oct at 1. " +
                                "Turning it up widens the skirt into the low mids, which costs cancellation there.",
                    ),
                    // defaults to 0: any Width mixes the voice back in and limits the cancellation
                    ParamSpec(
                        "width",
                        "Width",
                        default = 0f,
                        help =
                            "Mixes the untouched stereo back over the result. " +
                                "It carries the voice with it, so any Width at all is a floor on how much of the singer goes: " +
                                "0.3 leaves the voice only 17 dB down where 0 reaches 60.",
                    ),
                ),
        )

    val modulation =
        EffectSpec(
            id = MODULATION,
            name = "Pitch Modulation",
            description =
                "LFO-swept delay: chorus (15–35 ms), flanger (1–6 ms) or a 6-stage all-pass phaser " +
                    "over 4 octaves, 0.05–8 Hz, with feedback",
            params =
                listOf(
                    ParamSpec(
                        id = "mode",
                        name = "Mode",
                        default = 1f,
                        choices = mapOf("Chorus" to 0f, "Flanger" to 1f, "Phaser" to 2f),
                        // a phaser is six all-pass sections; a chorus or flanger is a swept delay line
                        structural = true,
                    ),
                    ParamSpec(
                        "level",
                        "Level",
                        default = 0.5f,
                        unit = "pc",
                        help = "Wet against dry: 0 is all dry, 1 all swept.",
                    ),
                    ParamSpec("lfo", "LFO", default = 0f, help = "Sweep shape, blending from a sine at 0 to a triangle at 1."),
                    ParamSpec(
                        "depth",
                        "Depth",
                        default = 0.5f,
                        help = "How far the sweep travels: 1 to 6 ms flanging, 15 to 35 ms chorusing, four octaves phasing.",
                    ),
                    ParamSpec("rate", "Rate", default = 0.25f, help = "Sweep speed, 0.05 Hz to 8 Hz, exponential."),
                    ParamSpec(
                        "feedback",
                        "FBack",
                        default = 0.4f,
                        help = "How much output returns to the input. More is more resonant; a chorus keeps it low on purpose.",
                    ),
                    ParamSpec(
                        "stereo",
                        "Stereo",
                        default = 0.5f,
                        help = "Sweep offset between channels, from together at 0 to half a cycle apart at 1.",
                        snap = listOf(0.5f),
                    ),
                ),
        )

    val reverb =
        EffectSpec(
            id = REVERB,
            name = "Reverb",
            description =
                "Feedback delay network: six damped combs mixed through a Householder matrix into " +
                    "three all-passes, four early reflections, T60 0.3–6 s",
            params =
                listOf(
                    ParamSpec(
                        "decay",
                        "Decay",
                        // in seconds, to match the unit the readout shows
                        min = MIN_DECAY_SECONDS,
                        max = MAX_DECAY_SECONDS,
                        default = 1.2f,
                        unit = "s",
                        displayDecimals = 1,
                        help =
                            "How long the tail takes to fade, 0.3 to 6 seconds. Size sets how big the " +
                                "room sounds and this sets how long it rings, so a big room can be a short one.",
                    ),
                    ParamSpec(
                        "level",
                        "Level",
                        default = 0.25f,
                        unit = "pc",
                        help =
                            "Wet against dry: 0 is no room at all. The room is fed both channels summed, " +
                                "so turning this up also pulls a pan above it back towards the middle.",
                    ),
                    ParamSpec(
                        "size",
                        "Size",
                        default = 0.5f,
                        help = "How big the room is, roughly 9 ms to 59 ms between reflections. How long it rings is Decay's.",
                    ),
                    ParamSpec(
                        "near",
                        "Near",
                        default = 0.5f,
                        help = "Balance of early reflections against the tail. Turning it up walks you towards the source.",
                    ),
                    ParamSpec(
                        "air",
                        "Air",
                        default = 0.5f,
                        help = "How much top end each bounce keeps. Low is a dead room, high a tiled one.",
                    ),
                ),
        )

    /** The built-in effects, in their default signal order. */
    val all = listOf(karaoke, modulation, reverb)

    val ids = all.map { it.id }

    fun byId(id: String): EffectSpec? = all.firstOrNull { it.id == id }

    /** A rack with every bundled effect present, in order, switched off. */
    val defaultRack: RackSettings
        get() = RackSettings(all.map { RackSlot(it.id, enabled = false, params = it.defaults) })
}
