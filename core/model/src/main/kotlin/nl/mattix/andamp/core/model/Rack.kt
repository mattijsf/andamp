// SPDX-License-Identifier: Apache-2.0

package nl.mattix.andamp.core.model

/**
 * An effect's parameter values, by parameter id. Every value is a number: a toggle is 0 or
 * 1 and a choice is its declared value.
 */
@JvmInline
value class ParamValues(
    val values: Map<String, Float> = emptyMap(),
) {
    /** The stored value, or [default] when this effect has never been touched. */
    operator fun get(
        id: String,
        default: Float,
    ): Float = values[id] ?: default

    fun with(
        id: String,
        value: Float,
    ): ParamValues = ParamValues(values + (id to value))

    companion object {
        val EMPTY = ParamValues()
    }
}

/**
 * One effect in the rack: which plug-in, whether it is switched on, and what
 * its controls are set to.
 */
data class RackSlot(
    val pluginId: String,
    val enabled: Boolean = false,
    val params: ParamValues = ParamValues.EMPTY,
)

/**
 * The effect rack, in signal order: the first slot sees the audio first. An effect that is
 * off is skipped.
 */
data class RackSettings(
    val slots: List<RackSlot> = emptyList(),
    /**
     * The peak limiter after the rack: on, it turns the volume down ahead of any peak that
     * would pass -1 dBFS; off, the audio is untouched and a peak past full scale is clipped
     * where it becomes 16-bit.
     *
     * It is not a slot because its place is fixed, last. Off by default.
     */
    val limiter: Boolean = false,
) {
    operator fun get(pluginId: String): RackSlot? = slots.firstOrNull { it.pluginId == pluginId }

    /** [pluginId] moved to [to], the rest closing up around it. */
    fun move(
        pluginId: String,
        to: Int,
    ): RackSettings {
        val from = slots.indexOfFirst { it.pluginId == pluginId }
        if (from < 0 || to !in slots.indices || from == to) return this
        val reordered = slots.toMutableList()
        reordered.add(to, reordered.removeAt(from))
        return copy(slots = reordered)
    }

    fun setEnabled(
        pluginId: String,
        enabled: Boolean,
    ): RackSettings = mapSlot(pluginId) { it.copy(enabled = enabled) }

    fun setValue(
        pluginId: String,
        paramId: String,
        value: Float,
    ): RackSettings = mapSlot(pluginId) { it.copy(params = it.params.with(paramId, value)) }

    /**
     * Sets several of one plug-in's values in one edit, as a preset does, so the listener
     * hears one change and not a sweep through the controls.
     */
    fun setValues(
        pluginId: String,
        values: Map<String, Float>,
    ): RackSettings =
        mapSlot(pluginId) { slot ->
            slot.copy(params = values.entries.fold(slot.params) { at, (id, value) -> at.with(id, value) })
        }

    fun setLimiter(on: Boolean): RackSettings = copy(limiter = on)

    /**
     * The rack with [pluginId] first, when it is present, and everything else in the order
     * it had.
     */
    fun withFirst(pluginId: String): RackSettings {
        val at = slots.indexOfFirst { it.pluginId == pluginId }
        if (at <= 0) return this
        return copy(slots = listOf(slots[at]) + slots.filterIndexed { i, _ -> i != at })
    }

    /** Drops a plug-in from the rack; its stored state goes with it. */
    fun remove(pluginId: String): RackSettings = copy(slots = slots.filterNot { it.pluginId == pluginId })

    /**
     * The rack with each of [pluginIds] present, keeping the order and settings of any
     * already here. Slots whose id is not in [pluginIds] are dropped and new ids are appended.
     */
    fun reconcile(pluginIds: List<String>): RackSettings {
        val known = pluginIds.toSet()
        val kept = slots.filter { it.pluginId in known }
        val added = pluginIds.filterNot { id -> kept.any { it.pluginId == id } }.map { RackSlot(it) }
        return copy(slots = kept + added)
    }

    private fun mapSlot(
        pluginId: String,
        transform: (RackSlot) -> RackSlot,
    ): RackSettings = copy(slots = slots.map { if (it.pluginId == pluginId) transform(it) else it })

    companion object {
        val EMPTY = RackSettings()
    }
}
