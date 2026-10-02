// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import android.content.SharedPreferences

/** A value kept for one track, and the key it is filed under. */
data class Keyed<T>(
    val key: String,
    val value: T,
)

/**
 * What a listener sets for one song: the equalizer's auto-load curve. Keys come from
 * [nl.mattix.andamp.core.model.TrackKey], so a song reached two ways has one key.
 */
interface PerTrackStore<T : Any> {
    fun get(key: String): T?

    /** Everything saved, for a menu that lists or deletes them. */
    fun all(): List<Keyed<T>>

    fun put(
        key: String,
        value: T,
    )

    fun remove(key: String)
}

/**
 * A [PerTrackStore] over shared preferences: one entry per track under
 * [prefix], encoded by the feature that owns the value.
 */
class PrefsPerTrackStore<T : Any>(
    private val prefs: SharedPreferences,
    private val prefix: String,
    private val encode: (T) -> String,
    private val decode: (String) -> T?,
    /** The string a listing is sorted by; the key by default. */
    private val order: (Keyed<T>) -> String = { it.key },
) : PerTrackStore<T> {
    override fun get(key: String): T? = prefs.getString(prefix + key, null)?.let(decode)

    override fun all(): List<Keyed<T>> =
        prefs.all.keys
            .filter { it.startsWith(prefix) }
            .mapNotNull { stored ->
                prefs.getString(stored, null)?.let(decode)?.let { Keyed(stored.removePrefix(prefix), it) }
            }.sortedBy { order(it).lowercase() }

    override fun put(
        key: String,
        value: T,
    ) {
        prefs.edit().putString(prefix + key, encode(value)).apply()
    }

    override fun remove(key: String) {
        prefs.edit().remove(prefix + key).apply()
    }
}

/** The same, in memory. */
class InMemoryPerTrackStore<T : Any>(
    private val order: (Keyed<T>) -> String = { it.key },
) : PerTrackStore<T> {
    private val values = mutableMapOf<String, T>()

    override fun get(key: String): T? = values[key]

    override fun all(): List<Keyed<T>> = values.entries.map { Keyed(it.key, it.value) }.sortedBy { order(it).lowercase() }

    override fun put(
        key: String,
        value: T,
    ) {
        values[key] = value
    }

    override fun remove(key: String) {
        values.remove(key)
    }
}
