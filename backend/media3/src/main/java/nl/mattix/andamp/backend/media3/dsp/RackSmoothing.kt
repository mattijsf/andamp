// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.backend.media3.dsp

import nl.mattix.andamp.core.model.EffectSpec
import nl.mattix.andamp.core.model.ParamValues
import nl.mattix.andamp.core.model.RackSettings
import kotlin.math.abs

/**
 * The rack's values as the audio thread hears them: walking toward where the
 * sliders are.
 *
 * A control value that jumps is a step in the signal, and a step is a click.
 * Walking the value over [SMOOTH_MS] makes it a glide, as
 * docs/dsp-plugin-spec.md section 5 describes for plug-in parameters.
 *
 * The walk treats a slot's parameters as a map of numbers and does not know
 * what each number means.
 */
internal class RackSmoothing {
    /** Where the sliders are. Written from the thread that changes the rack, read on the audio thread. */
    @Volatile
    var target: RackSettings = RackSettings.EMPTY
        private set

    /**
     * Where the audio is.
     *
     * Stepped by the audio thread at every tick and re-aimed from the thread
     * that moved a slider. [aim], [tick], [settle] and [configure] are
     * synchronized on this object, so a tick cannot write back an arrangement
     * an aim has just replaced.
     */
    @Volatile
    var current: RackSettings = RackSettings.EMPTY
        private set

    private var coefficient = 0f

    /**
     * The controls that jump to their value, as "plugin.param".
     *
     * A choice is not a quantity: walking one from 2 to 0 would pass through
     * 1, which is another option.
     */
    private var jumps: Set<String> = emptySet()

    /** True while a value is still traveling, so the caller knows to re-apply. */
    @Volatile
    var moving = false
        private set

    @Synchronized
    fun configure(
        sampleRate: Int,
        catalogue: List<EffectSpec> = emptyList(),
    ) {
        jumps =
            catalogue
                .flatMap { spec -> spec.params.filter { it.isChoice }.map { "${spec.id}.${it.id}" } }
                .toSet()
        // one-pole step per control tick, from SMOOTH_MS
        val ticksPerSecond = sampleRate.toFloat() / CONTROL_PERIOD
        coefficient = (1f / (ticksPerSecond * SMOOTH_MS / MS_PER_SEC)).coerceIn(0f, 1f)
    }

    /**
     * Aims at [rack]. A slot that is new, or whose switch moved, takes its
     * values at once: it is not being heard yet, and the fade in
     * [DspAudioProcessor] covers its arrival.
     */
    @Synchronized
    fun aim(rack: RackSettings) {
        target = rack
        current =
            RackSettings(
                rack.slots.map { wanted ->
                    val here = current[wanted.pluginId]
                    if (here == null || here.enabled != wanted.enabled) wanted else wanted.copy(params = here.params)
                },
            )
        moving = true
    }

    /** Snaps every value to its target: for a flush, where nothing may be mid-glide. */
    @Synchronized
    fun settle() {
        current = target
        moving = false
    }

    /**
     * One control tick toward [target]. Returns true when something moved, so
     * the caller only pushes settings that changed.
     */
    @Synchronized
    fun tick(): Boolean {
        if (!moving) return false
        var travelling = false
        current =
            RackSettings(
                current.slots.map { slot ->
                    val wanted = target[slot.pluginId] ?: return@map slot
                    val stepped = step(slot.pluginId, slot.params, wanted.params)
                    if (stepped != null) travelling = true
                    slot.copy(params = stepped ?: wanted.params)
                },
            )
        moving = travelling
        return true
    }

    /** One step of every value toward [to], or null once they have all arrived. */
    private fun step(
        pluginId: String,
        from: ParamValues,
        to: ParamValues,
    ): ParamValues? {
        var moved = false
        val walked = HashMap<String, Float>(to.values.size)
        to.values.forEach { (id, wanted) ->
            val here = from[id, wanted]
            val next =
                if ("$pluginId.$id" in jumps || abs(wanted - here) < CLOSE_ENOUGH) {
                    wanted
                } else {
                    moved = true
                    here + (wanted - here) * coefficient
                }
            walked[id] = next
        }
        return if (moved) ParamValues(walked) else null
    }

    private companion object {
        /** Frames between control ticks: 0.73 ms at 44.1 kHz. */
        const val CONTROL_PERIOD = 32

        /** The default smoothing time in docs/dsp-plugin-spec.md, in milliseconds. */
        const val SMOOTH_MS = 20f
        const val MS_PER_SEC = 1000f

        /** Closer to its target than this, a value lands on it and stops. */
        const val CLOSE_ENOUGH = 1e-3f
    }

    /** Frames between control ticks. */
    val controlPeriod get() = CONTROL_PERIOD
}
