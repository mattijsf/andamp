// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import android.content.SharedPreferences
import nl.mattix.andamp.core.model.BuiltInEffects
import nl.mattix.andamp.core.model.ParamValues
import nl.mattix.andamp.core.model.RackSettings
import nl.mattix.andamp.core.model.RackSlot

/**
 * The rack in SharedPreferences: the order, which slots are on, and every control's value,
 * keyed by plug-in id.
 *
 * Keys are flat and prefixed by the plug-in, `<pluginId>.<paramId>`. A parameter added in
 * a later version reads as its default and leaves the stored ones valid, and removing a
 * plug-in's state is a prefix sweep.
 *
 * Ids are reverse-DNS, so one can begin with another: `a.b.c.depth` is plug-in `a.b.c`'s
 * depth, not a parameter of `a.b` called `c.depth`. Every prefix read goes through
 * [keysOf], which gives a key to the longest id the rack knows that matches it.
 */
object RackPrefsCodec {
    private const val ORDER = "rack.order"
    private const val LIMITER = "rack.limiter"
    private const val ENABLED = "enabled."

    /** The prefix of the values [remember] stores, apart from the rack's current ones. */
    private const val MINE = "mine."
    private const val SEPARATOR = ","
    private const val ENABLED_SUFFIX = ".on"

    /** A key of an older saved format (a set of enabled effects). It is not read; [decode] removes it. */
    private const val LEGACY_SET = "enabled"

    /**
     * The effect prefixes of an older saved format, in the order that rack ran them,
     * mapped to plug-in ids. See [migrateFixedRack].
     */
    private val LEGACY_PREFIXES =
        linkedMapOf(
            "karaoke" to BuiltInEffects.KARAOKE,
            "mod" to BuiltInEffects.MODULATION,
            "reverb" to BuiltInEffects.REVERB,
        )

    fun decode(
        prefs: SharedPreferences,
        available: List<String>,
    ): RackSettings {
        // removed on every decode, also for a device whose order has already been migrated
        if (prefs.contains(LEGACY_SET)) prefs.edit().remove(LEGACY_SET).apply()
        if (!prefs.contains(ORDER)) migrateFixedRack(prefs)
        val stored =
            prefs
                .getString(ORDER, null)
                ?.split(SEPARATOR)
                ?.filter { it.isNotBlank() }
                .orEmpty()
        // stored order first, then anything installed since; reconcile drops plug-ins that
        // are not available
        val order = (stored + available).distinct()
        val slots =
            order.map { id ->
                RackSlot(
                    pluginId = id,
                    enabled = prefs.getBoolean(ENABLED + id, false),
                    params = ParamValues(floatsOf(prefs, id)),
                )
            }
        return RackSettings(slots, limiter = prefs.getBoolean(LIMITER, false)).reconcile(available)
    }

    fun encode(
        prefs: SharedPreferences,
        rack: RackSettings,
    ) {
        val edit = prefs.edit()
        edit.putString(ORDER, rack.slots.joinToString(SEPARATOR) { it.pluginId })
        edit.putBoolean(LIMITER, rack.limiter)
        rack.slots.forEach { slot ->
            edit.putBoolean(ENABLED + slot.pluginId, slot.enabled)
            slot.params.values.forEach { (paramId, value) ->
                edit.putFloat("${slot.pluginId}.$paramId", value)
            }
        }
        edit.apply()
    }

    /**
     * Stores this effect's values in a slot of their own, one per effect, so the listener
     * can try other settings and come back; see [remembered].
     */
    fun remember(
        prefs: SharedPreferences,
        pluginId: String,
        values: ParamValues,
    ) {
        val edit = prefs.edit()
        keysOf(prefs, pluginId, MINE).forEach(edit::remove)
        values.values.forEach { (id, value) -> edit.putFloat("$MINE$pluginId.$id", value) }
        edit.putBoolean("$MINE$pluginId", true).apply()
    }

    /** The values [remember] stored, or null when there are none. */
    fun remembered(
        prefs: SharedPreferences,
        pluginId: String,
    ): ParamValues? {
        if (!prefs.getBoolean("$MINE$pluginId", false)) return null
        return ParamValues(floatsOf(prefs, pluginId, MINE))
    }

    /**
     * Removes everything stored for a plug-in: its order entry, its switch, its values and
     * the values [remember] stored.
     */
    fun forget(
        prefs: SharedPreferences,
        pluginId: String,
    ) {
        val edit = prefs.edit()
        edit.remove(ENABLED + pluginId)
        edit.remove("$MINE$pluginId")
        (keysOf(prefs, pluginId) + keysOf(prefs, pluginId, MINE)).forEach(edit::remove)
        val order =
            prefs
                .getString(ORDER, null)
                ?.split(SEPARATOR)
                ?.filterNot { it == pluginId }
                ?.joinToString(SEPARATOR)
        if (order != null) edit.putString(ORDER, order)
        edit.apply()
    }

    /**
     * Migration of a saved value: the keys of the older fixed rack, which had no plug-in
     * prefix (`reverb.size`), are moved to the ordered rack's keys and removed. The order
     * written is the order that rack ran in, so the sound is the same after the migration.
     */
    private fun migrateFixedRack(prefs: SharedPreferences) {
        val legacy = prefs.all
        val edit = prefs.edit()
        if (LEGACY_PREFIXES.keys.any { legacy.containsKey("$it$ENABLED_SUFFIX") }) {
            LEGACY_PREFIXES.forEach { (old, pluginId) ->
                edit.putBoolean(ENABLED + pluginId, legacy["$old$ENABLED_SUFFIX"] as? Boolean ?: false)
                edit.remove("$old$ENABLED_SUFFIX")
                moveValues(edit, legacy, old, pluginId)
            }
            edit.putString(ORDER, LEGACY_PREFIXES.values.joinToString(SEPARATOR))
        }
        edit.apply()
    }

    /** Moves one old effect's values under its plug-in's prefix. */
    private fun moveValues(
        edit: SharedPreferences.Editor,
        legacy: Map<String, Any?>,
        old: String,
        pluginId: String,
    ) {
        legacy.forEach { (key, value) ->
            if (!key.startsWith("$old.") || key.endsWith(ENABLED_SUFFIX)) return@forEach
            // the old format stored the mode as an enum name; the value stored is the choice's number
            val number = value as? Float ?: (value as? String)?.let { modeIndex(it) } ?: return@forEach
            edit.putFloat("$pluginId.${key.removePrefix("$old.")}", number)
            edit.remove(key)
        }
    }

    private fun modeIndex(name: String): Float? =
        BuiltInEffects.modulation.params
            .first { it.id == "mode" }
            .choices
            ?.entries
            ?.firstOrNull { it.key.equals(name, ignoreCase = true) }
            ?.value

    /** This plug-in's stored numbers under [area], keyed by parameter id. */
    private fun floatsOf(
        prefs: SharedPreferences,
        pluginId: String,
        area: String = "",
    ): Map<String, Float> {
        val all = prefs.all
        val prefix = "$area$pluginId."
        return keysOf(prefs, pluginId, area)
            .mapNotNull { key -> (all[key] as? Float)?.let { key.removePrefix(prefix) to it } }
            .toMap()
    }

    /**
     * The keys under [area] that belong to [pluginId]: those that start with
     * its id and a dot, less those a longer id the rack also knows claims.
     */
    private fun keysOf(
        prefs: SharedPreferences,
        pluginId: String,
        area: String = "",
    ): List<String> {
        val all = prefs.all
        val nested = knownIds(prefs, all).filter { it.startsWith("$pluginId.") }
        return all.keys.filter { key ->
            // a nested id's own remembered-values marker, `mine.a.b.c`, has no dot after it and
            // belongs to that plug-in too
            key.startsWith("$area$pluginId.") && nested.none { key == "$area$it" || key.startsWith("$area$it.") }
        }
    }

    /**
     * Every plug-in id something is stored for: the order, every switch, and every
     * remembered-values marker. A plug-in with values always has a switch, since [encode]
     * writes both.
     */
    private fun knownIds(
        prefs: SharedPreferences,
        all: Map<String, *>,
    ): Set<String> =
        buildSet {
            prefs
                .getString(ORDER, null)
                ?.split(SEPARATOR)
                ?.filter { it.isNotBlank() }
                ?.let(::addAll)
            all.keys.filter { it.startsWith(ENABLED) }.forEach { add(it.removePrefix(ENABLED)) }
            all.filter { (key, value) -> key.startsWith(MINE) && value is Boolean }.keys.forEach { add(it.removePrefix(MINE)) }
        }
}
