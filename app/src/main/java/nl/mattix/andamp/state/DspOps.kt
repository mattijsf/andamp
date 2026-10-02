// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import nl.mattix.andamp.core.model.BuiltInEffects
import nl.mattix.andamp.core.model.EffectSpec
import nl.mattix.andamp.core.model.RackSettings
import nl.mattix.andamp.core.player.PlayerFacade

/**
 * Preferences > Effects: the rack, its order, and what is on.
 *
 * The list is the signal order, top to bottom. The rack is stored, and pushed to the
 * backend whole: the audio thread reads one immutable rack. A backend without
 * [nl.mattix.andamp.core.model.Capabilities.hasDsp] is not sent a rack.
 */
class DspOps(
    context: Context,
    private val facade: PlayerFacade,
) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The effects on offer, asked of the backend: built-in effects and installed plug-ins alike. */
    var catalogue: List<EffectSpec> by mutableStateOf(facade.effects)
        private set

    val available: Boolean get() = facade.capabilities.hasDsp

    /** Snapshot state so the preferences page redraws when the rack changes. */
    var rack by mutableStateOf(RackPrefsCodec.decode(prefs, catalogue.map { it.id }).withFirst(BuiltInEffects.PREAMP))
        private set

    /** Whether [pluginId] keeps a fixed place in the rack and cannot be moved. */
    fun pinned(pluginId: String) = pluginId == BuiltInEffects.PREAMP

    /** Pushes the stored rack to the backend. */
    fun start() = facade.setDsp(rack)

    /**
     * Asks the backend what it can run, after a plug-in was installed or removed. The rack
     * is decoded again against the new list, so a new plug-in joins the end switched off, a
     * removed one leaves, and the rest keep their settings.
     */
    fun refresh() {
        catalogue = facade.effects
        update(RackPrefsCodec.decode(prefs, catalogue.map { it.id }))
    }

    fun setEnabled(
        pluginId: String,
        enabled: Boolean,
    ) = update(rack.setEnabled(pluginId, enabled))

    fun setValue(
        pluginId: String,
        paramId: String,
        value: Float,
    ) = update(rack.setValue(pluginId, paramId, value))

    /** Applies a preset: several values in one edit. */
    fun apply(
        pluginId: String,
        values: Map<String, Float>,
    ) = update(rack.setValues(pluginId, values))

    /** Stores this effect's current values, for [restore]. */
    fun remember(pluginId: String) {
        rack[pluginId]?.let { RackPrefsCodec.remember(prefs, pluginId, it.params) }
    }

    /** Puts the values stored by [remember] back; does nothing when none are stored. */
    fun restore(pluginId: String) {
        RackPrefsCodec.remembered(prefs, pluginId)?.let { update(rack.setValues(pluginId, it.values)) }
    }

    fun hasRemembered(pluginId: String) = RackPrefsCodec.remembered(prefs, pluginId) != null

    /** Sets the effect's controls to its own defaults. */
    fun reset(pluginId: String) {
        catalogue.firstOrNull { it.id == pluginId }?.let { update(rack.setValues(pluginId, it.defaults.values)) }
    }

    /** Puts a plug-in at [position] in the chain, for a drag. */
    fun moveTo(
        pluginId: String,
        position: Int,
    ) = update(rack.move(pluginId, position))

    /** Moves a plug-in one place earlier in the signal, if it is not already first. */
    fun moveUp(pluginId: String) = moveBy(pluginId, -1)

    fun moveDown(pluginId: String) = moveBy(pluginId, 1)

    /** Drops a plug-in and everything stored for it. */
    fun remove(pluginId: String) {
        RackPrefsCodec.forget(prefs, pluginId)
        update(rack.remove(pluginId))
    }

    fun specFor(pluginId: String): EffectSpec? = catalogue.firstOrNull { it.id == pluginId }

    private fun moveBy(
        pluginId: String,
        delta: Int,
    ) {
        val from = rack.slots.indexOfFirst { it.pluginId == pluginId }
        if (from < 0) return
        update(rack.move(pluginId, (from + delta).coerceIn(rack.slots.indices)))
    }

    /** The peak limiter after the rack; see [RackSettings.limiter]. */
    fun setLimiter(on: Boolean) = update(rack.setLimiter(on))

    private fun update(updated: RackSettings) {
        // the Preamp stays first: a move that would put another effect above it lands just
        // below it
        val kept = updated.withFirst(BuiltInEffects.PREAMP)
        rack = kept
        RackPrefsCodec.encode(prefs, kept)
        facade.setDsp(kept)
    }

    private companion object {
        const val PREFS = "dsp"
    }
}
