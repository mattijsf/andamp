// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.prefs

import nl.mattix.andamp.core.model.EffectSpec
import nl.mattix.andamp.core.model.ParamValues
import nl.mattix.andamp.core.model.Preset
import nl.mattix.andamp.core.model.RackSlot
import kotlin.math.abs

/**
 * One line that says what an effect is set to, for a collapsed card: the preset it matches, by
 * name; otherwise the first [SHOWN] controls that moved, with their values; otherwise "Default
 * settings".
 */
internal object RackDigest {
    fun statusOf(
        spec: EffectSpec,
        slot: RackSlot,
    ): String {
        spec.presets.firstOrNull { matches(spec, slot.params, it) }?.let { return it.name }
        val moved =
            spec.params
                .filterNot { it.isChoice }
                .filter { abs(slot.params[it.id, it.default] - it.default) > EPSILON }
        if (moved.isEmpty()) return "Default settings"
        val shown =
            moved.take(SHOWN).joinToString(" · ") { param ->
                "${param.name} ${display(param, slot.params[param.id, param.default])}"
            }
        return if (moved.size > SHOWN) "$shown …" else shown
    }

    /**
     * Whether these values are this preset, to within [EPSILON]. A preset need not mention every
     * control, and what it leaves out is not compared. A control the listener has not set reads as
     * its declared default.
     */
    fun matches(
        spec: EffectSpec,
        params: ParamValues,
        preset: Preset,
    ): Boolean =
        preset.values.all { (id, wanted) ->
            val declared = spec.params.firstOrNull { it.id == id }?.default ?: wanted
            abs(params[id, declared] - wanted) <= EPSILON
        }

    /** The tolerance for comparing a value with a preset's or a default. */
    private const val EPSILON = 1e-3f

    /** How many moved controls the line names. */
    private const val SHOWN = 2
}
